package dev.tridentplus;

import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * The bootstrapper runs before the plugin instance exists, so it has to read
 * plugins/TridentPlus/config.yml itself. Only registry-time values are needed here:
 * the Fork display name is baked into the enchantment registry entry and therefore
 * cannot be hot-reloaded.
 */
final class BootstrapConfig {

    static final String DEFAULT_FORK_DISPLAY_NAME = "Нанизывание";

    private BootstrapConfig() {
    }

    /**
     * Makes sure config.yml exists and returns the configured Fork display name.
     * Never throws: a broken config must not prevent the server from starting.
     */
    static String loadForkDisplayName(final BootstrapContext context) {
        final Path dataDirectory = context.getDataDirectory();
        final Path configPath = dataDirectory.resolve("config.yml");
        try {
            if (Files.notExists(configPath)) {
                Files.createDirectories(dataDirectory);
                try (InputStream in = TridentPlusBootstrap.class.getClassLoader()
                        .getResourceAsStream("config.yml")) {
                    if (in == null) {
                        return DEFAULT_FORK_DISPLAY_NAME;
                    }
                    Files.copy(in, configPath);
                }
                context.getLogger().info("Created default config.yml");
            }
            if (!Files.isRegularFile(configPath)) {
                return DEFAULT_FORK_DISPLAY_NAME;
            }
            final YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(configPath.toFile());
            final String name = yaml.getString("fork.display-name", DEFAULT_FORK_DISPLAY_NAME);
            return name == null || name.isBlank() ? DEFAULT_FORK_DISPLAY_NAME : name;
        } catch (final IOException | InvalidConfigurationException | RuntimeException ex) {
            context.getLogger().warn("Could not read config.yml during bootstrap; using defaults: {}", ex.getMessage());
            return DEFAULT_FORK_DISPLAY_NAME;
        }
    }
}
