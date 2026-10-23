package dev.tridentplus.resourcepack;

import com.sun.net.httpserver.HttpServer;
import dev.tridentplus.TridentPlusPlugin;
import dev.tridentplus.config.Settings;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;

/**
 * Builds the tiny translation pack, serves it over HTTP and sends it to players.
 *
 * <p>The pack contains only three language files (en_us, ru_ru, rpr). It is
 * generated from resources inside this jar, so dropping the plugin jar on a
 * server is enough; no external pack file has to be installed. The generated
 * zip is also written to the plugin data directory so an administrator can host
 * it externally and set resource-pack.public-url when the game port's host
 * blocks arbitrary HTTP ports.
 */
public final class ResourcePackService implements Listener {

    private static final String PACK_PATH = "/tridentplus-pack.zip";
    private static final UUID PACK_ID = UUID.nameUUIDFromBytes(
            "tridentplus:resourcepack".getBytes(StandardCharsets.UTF_8));

    private final TridentPlusPlugin plugin;
    private volatile HttpServer server;
    private volatile ExecutorService executor;
    private volatile byte[] pack;
    private volatile byte[] hash;
    private volatile String publicUrl;
    private volatile String hostOverride;
    private volatile int port;
    private boolean sendErrorLogged;

    public ResourcePackService(final TridentPlusPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        stop();
        this.sendErrorLogged = false;
        final Settings.ResourcePack config = this.plugin.settings().resourcePack();
        if (!config.enabled()) {
            this.plugin.getLogger().info("Resource pack disabled; Fork name uses the literal fallback.");
            return;
        }

        try {
            this.pack = ResourcePackBuilder.build(getClass().getClassLoader());
            this.hash = sha1(this.pack);
            final Path output = this.plugin.getDataFolder().toPath()
                    .resolve("TridentPlus-resourcepack.zip");
            Files.createDirectories(output.getParent());
            Files.write(output, this.pack);
            this.plugin.getLogger().info("Resource pack written to " + output);

            this.publicUrl = config.publicUrl().isBlank() ? null : config.publicUrl();
            this.hostOverride = config.host().isBlank() ? null : config.host();

            if (this.publicUrl != null) {
                this.port = config.port();
                this.plugin.getLogger().info("Using external resource pack URL: " + this.publicUrl);
                return;
            }

            startHttpServer(config.port());
            this.port = this.server.getAddress().getPort();
            this.plugin.getLogger().info("Serving resource pack on port " + this.port
                    + "; the host is taken from each player's connection address, "
                    + "with fallback " + detectHost() + ". Set resource-pack.host/public-url "
                    + "if clients cannot reach it.");
        } catch (final IOException | RuntimeException | LinkageError ex) {
            // LinkageError covers a JDK image without the jdk.httpserver module;
            // the plugin must still start and use the literal fallback.
            this.plugin.getLogger().warning(
                    "Could not start the resource pack service: " + ex.getMessage());
            stop();
        }
    }

    public void reload() {
        start();
        for (final Player player : Bukkit.getOnlinePlayers()) {
            send(player);
        }
    }

    public void stop() {
        final HttpServer current = this.server;
        this.server = null;
        if (current != null) {
            current.stop(0);
        }
        final ExecutorService currentExecutor = this.executor;
        this.executor = null;
        if (currentExecutor != null) {
            currentExecutor.shutdownNow();
        }
        this.publicUrl = null;
        this.hostOverride = null;
        this.port = 0;
        this.hash = null;
        this.pack = null;
    }

    public void send(final Player player) {
        final byte[] currentHash = this.hash;
        if (currentHash == null) {
            return;
        }
        final String url = urlFor(player);
        if (url == null) {
            return;
        }
        try {
            player.setResourcePack(PACK_ID, url, currentHash,
                    Component.text(this.plugin.settings().resourcePack().prompt()), false);
        } catch (final RuntimeException ex) {
            if (!this.sendErrorLogged) {
                this.sendErrorLogged = true;
                this.plugin.getLogger().warning(
                        "Could not send the resource pack: " + ex.getMessage());
            }
        }
    }

    /**
     * Uses the public URL when configured, then the explicit host, then the
     * hostname the player actually typed in their multiplayer list (Paper's
     * virtual host), and finally the server's detected LAN address.
     */
    private String urlFor(final Player player) {
        if (this.publicUrl != null) {
            return this.publicUrl;
        }
        if (this.port <= 0) {
            return null;
        }
        String host = this.hostOverride;
        if (host == null && player != null) {
            final InetSocketAddress virtualHost = player.getVirtualHost();
            if (virtualHost != null) {
                final String candidate = virtualHost.getHostString();
                if (candidate != null && !candidate.isBlank()
                        && !"0.0.0.0".equals(candidate) && !"::".equals(candidate)) {
                    host = candidate;
                }
            }
        }
        if (host == null) {
            host = detectHost();
        }
        return "http://" + formatHost(host) + ":" + this.port + PACK_PATH;
    }

    private void startHttpServer(final int configuredPort) throws IOException {
        final int bindPort = configuredPort == 0 ? 0 : configuredPort;
        final HttpServer newServer = HttpServer.create(new InetSocketAddress(bindPort), 0);
        newServer.createContext(PACK_PATH, exchange -> {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
                return;
            }
            final byte[] data = this.pack;
            if (data == null) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", "application/zip");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.sendResponseHeaders(200, data.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(data);
            }
        });
        final ExecutorService newExecutor = Executors.newSingleThreadExecutor(runnable -> {
            final Thread thread = new Thread(runnable, "TridentPlus-ResourcePack");
            thread.setDaemon(true);
            return thread;
        });
        newServer.setExecutor(newExecutor);
        newServer.start();
        this.server = newServer;
        this.executor = newExecutor;
    }

    private static byte[] sha1(final byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-1").digest(data);
        } catch (final NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-1 is required by the JDK", ex);
        }
    }

    private static String detectHost() {
        final String configured = Bukkit.getIp();
        if (configured != null && !configured.isBlank()
                && !"0.0.0.0".equals(configured) && !"::".equals(configured)) {
            return configured;
        }
        try {
            for (final NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!network.isUp() || network.isLoopback() || network.isVirtual()
                        || isVirtualName(network.getName())) {
                    continue;
                }
                for (final InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (address instanceof Inet4Address && address.isSiteLocalAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (final SocketException ignored) {
            // Fall through to loopback.
        }
        return "127.0.0.1";
    }

    private static boolean isVirtualName(final String name) {
        final String lower = name.toLowerCase(Locale.ROOT);
        return lower.startsWith("docker") || lower.startsWith("br-")
                || lower.startsWith("veth") || lower.startsWith("virbr")
                || lower.startsWith("tun") || lower.startsWith("tap")
                || lower.startsWith("wg") || lower.startsWith("tailscale")
                || lower.startsWith("kube") || lower.startsWith("cni")
                || lower.startsWith("flannel");
    }

    private static String formatHost(final String host) {
        if (host.indexOf(':') >= 0 && !host.startsWith("[")) {
            return "[" + host + "]";
        }
        return host;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(final PlayerJoinEvent event) {
        // One tick later so the player is fully in play before the pack request.
        Bukkit.getScheduler().runTask(this.plugin, () -> send(event.getPlayer()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPackStatus(final PlayerResourcePackStatusEvent event) {
        if (!PACK_ID.equals(event.getID())) {
            return;
        }
        final PlayerResourcePackStatusEvent.Status status = event.getStatus();
        if (status == PlayerResourcePackStatusEvent.Status.DECLINED
                || status == PlayerResourcePackStatusEvent.Status.FAILED_DOWNLOAD
                || status == PlayerResourcePackStatusEvent.Status.INVALID_URL
                || status == PlayerResourcePackStatusEvent.Status.FAILED_RELOAD) {
            this.plugin.getLogger().warning(
                    "Resource pack " + status + " for " + event.getPlayer().getName()
                            + " (" + urlFor(event.getPlayer()) + "). "
                            + "Fork falls back to the literal name.");
        }
    }
}
