package dev.tridentplus;

import dev.tridentplus.command.TridentPlusCommand;
import dev.tridentplus.config.Settings;
import dev.tridentplus.debug.DebugReporter;
import dev.tridentplus.debug.SelfCheck;
import dev.tridentplus.fork.ForkService;
import dev.tridentplus.listener.DamageListener;
import dev.tridentplus.listener.ForkListener;
import dev.tridentplus.listener.LootListener;
import dev.tridentplus.resourcepack.ResourcePackService;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class TridentPlusPlugin extends JavaPlugin {

    private final AtomicReference<Settings> settings = new AtomicReference<>(Settings.defaults());
    private final Map<UUID, CooldownSample> cooldowns = new ConcurrentHashMap<>();
    private final Set<UUID> dummies = ConcurrentHashMap.newKeySet();

    private DebugReporter debug;
    private ForkService forkService;
    private ResourcePackService resourcePack;

    private record CooldownSample(int tick, float cooldown) {
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadSettings();

        this.debug = new DebugReporter(this);
        this.forkService = new ForkService(this);
        this.resourcePack = new ResourcePackService(this);

        getServer().getPluginManager().registerEvents(new DamageListener(this), this);
        getServer().getPluginManager().registerEvents(new ForkListener(this), this);
        getServer().getPluginManager().registerEvents(new LootListener(this), this);
        getServer().getPluginManager().registerEvents(this.resourcePack, this);

        this.resourcePack.start();
        registerCommands();

        // Run after all plugins are enabled so the registry and tags are final.
        Bukkit.getScheduler().runTask(this, () -> {
            final SelfCheck.Result result = new SelfCheck(this).run();
            getLogger().info("Startup selfcheck: " + (result.passed() ? "PASS" : "FAIL"));
            for (final String line : result.lines()) {
                getLogger().info("  " + line);
            }
        });
    }

    @Override
    public void onDisable() {
        if (this.resourcePack != null) {
            this.resourcePack.stop();
        }
        this.cooldowns.clear();
        this.dummies.clear();
    }

    public void reloadSettings() {
        reloadConfig();
        this.settings.set(Settings.load(getConfig()));
    }

    public Settings settings() {
        return this.settings.get();
    }

    public DebugReporter debug() {
        return this.debug;
    }

    public ForkService forkService() {
        return this.forkService;
    }

    public ResourcePackService resourcePack() {
        return this.resourcePack;
    }

    public Enchantment forkEnchantment() {
        final NamespacedKey key = TridentPlusKeys.FORK_NAMESPACED_KEY;
        return RegistryAccess.registryAccess()
                .getRegistry(RegistryKey.ENCHANTMENT)
                .get(key);
    }

    public void rememberCooldown(final Player player) {
        this.cooldowns.put(player.getUniqueId(),
                new CooldownSample(Bukkit.getCurrentTick(), player.getAttackCooldown()));
    }

    /**
     * Uses the value captured in PrePlayerAttackEntityEvent when it belongs to the
     * current tick, otherwise falls back to the live value (e.g. damage caused by
     * another plugin without a swing).
     */
    public float attackCooldown(final Player player) {
        final CooldownSample sample = this.cooldowns.get(player.getUniqueId());
        if (sample != null && sample.tick() == Bukkit.getCurrentTick()) {
            return sample.cooldown();
        }
        return player.getAttackCooldown();
    }

    public void markDummy(final LivingEntity entity) {
        this.dummies.add(entity.getUniqueId());
    }

    public boolean isDummy(final LivingEntity entity) {
        return this.dummies.contains(entity.getUniqueId());
    }

    public void forgetDummy(final LivingEntity entity) {
        this.dummies.remove(entity.getUniqueId());
    }

    private void registerCommands() {
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register(
                        TridentPlusCommand.build(this),
                        "TridentPlus administration and testing",
                        List.of()
                ));
    }
}
