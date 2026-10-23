package dev.tridentplus.listener;

import dev.tridentplus.TridentPlusPlugin;
import dev.tridentplus.config.Settings;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Trident;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.world.LootGenerateEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public final class LootListener implements Listener {

    private final TridentPlusPlugin plugin;
    private final AtomicBoolean warnedThrown = new AtomicBoolean();

    public LootListener(final TridentPlusPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Shipwreck treasure and buried treasure have no enchantment rolls in 26.2,
     * and shipwreck supply only rolls on_random_loot for leather armour. None of
     * those can ever produce a Fork book, so the chests get an explicit injection.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLootGenerate(final LootGenerateEvent event) {
        final Settings settings = this.plugin.settings();
        if (!settings.lootInjection().enabled()) {
            return;
        }
        final String tableKey = event.getLootTable().getKey().toString();
        final Settings.TableRule rule = settings.lootInjection().tables().get(tableKey);
        if (rule == null || ThreadLocalRandom.current().nextDouble() >= rule.chance()) {
            return;
        }
        final Enchantment fork = this.plugin.forkEnchantment();
        if (fork == null) {
            return;
        }
        final int level = pickLevel(rule.levelWeights(), fork.getMaxLevel());
        if (level <= 0) {
            return;
        }

        final ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
        final ItemMeta meta = book.getItemMeta();
        if (meta == null) {
            return;
        }
        // addEnchant on an enchanted book writes the STORED_ENCHANTMENTS component.
        meta.addEnchant(fork, level, true);
        book.setItemMeta(meta);
        event.getLoot().add(book);
    }

    /**
     * Vanilla 26.2 Looting is implemented with loot functions that read the
     * attacker's equipped item. While a trident is in flight it is not in the
     * player's hand, so a thrown kill cannot see Looting. Bukkit's
     * LootContext#lootingModifier is ignored by the 26.2 loot implementation, so
     * there is no clean API-only way to add the extra drops here. We report the
     * limitation once instead of inventing drops.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onThrownLootingKill(final EntityDeathEvent event) {
        if (!this.plugin.settings().looting().applyToThrown()) {
            return;
        }
        if (!(event.getDamageSource().getDirectEntity() instanceof Trident trident)) {
            return;
        }
        if (trident.getItemStack().getEnchantmentLevel(Enchantment.LOOTING) <= 0) {
            return;
        }
        if (warnedThrown.compareAndSet(false, true)) {
            this.plugin.getLogger().warning(
                    "A thrown trident with Looting killed a mob. 26.2 applies Looting from the "
                            + "attacker's equipped item, not from a projectile, and the Bukkit loot "
                            + "API cannot override that cleanly. Vanilla drops are left unchanged "
                            + "(see README: known limitations).");
            final var killer = event.getEntity().getKiller();
            if (killer != null) {
                killer.sendMessage(Component.text(
                        "[TridentPlus] Thrown Looting is not applied by the 26.2 loot API; "
                                + "melee Looting works."));
            }
        }
    }

    private static int pickLevel(final List<Integer> weights, final int maxLevel) {
        if (weights == null || weights.isEmpty()) {
            return Math.max(1, maxLevel);
        }
        int total = 0;
        final int usable = Math.min(weights.size(), Math.max(1, maxLevel));
        for (int i = 0; i < usable; i++) {
            total += Math.max(0, weights.get(i));
        }
        if (total <= 0) {
            return 1;
        }
        int roll = ThreadLocalRandom.current().nextInt(total);
        for (int i = 0; i < usable; i++) {
            roll -= Math.max(0, weights.get(i));
            if (roll < 0) {
                return i + 1;
            }
        }
        return 1;
    }
}
