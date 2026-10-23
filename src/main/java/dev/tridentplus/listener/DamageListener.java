package dev.tridentplus.listener;

import dev.tridentplus.TridentPlusPlugin;
import dev.tridentplus.balance.DamageMath;
import dev.tridentplus.config.Settings;
import dev.tridentplus.fork.ForkService;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.damage.DamageType;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Trident;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Applies the config-driven Sharpness compensation.
 *
 * <p>Vanilla 26.2 already applies Sharpness to a trident (melee and thrown), so
 * the job here is to adjust the already-applied vanilla bonus to the desired one.
 * Only the BASE damage is changed; armor, resistance, absorption and other
 * modifiers are left alone.
 */
public final class DamageListener implements Listener {

    public enum Kind {
        MELEE, THROWN, RIPTIDE, FORK_SECONDARY
    }

    private final TridentPlusPlugin plugin;

    public DamageListener(final TridentPlusPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(final EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof LivingEntity target)) {
            return;
        }

        if (ForkService.isSecondaryHit()) {
            final ForkService.SecondaryContext context = ForkService.secondaryContext();
            if (context != null) {
                this.plugin.debug().damage(context.attacker(), Kind.FORK_SECONDARY,
                        context.sharpnessLevel(), context.forkLevel(), context.attackCooldown(),
                        event.getDamage(), event.getDamage(), event.getFinalDamage(), target);
            }
            // Never compensate or re-trigger Fork for our own secondary hits.
            return;
        }

        Player attacker;
        ItemStack weapon;
        Kind kind;

        if (event.getDamager() instanceof Player player) {
            if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) {
                return;
            }
            if (event.getDamageSource().getDamageType() != DamageType.PLAYER_ATTACK) {
                return;
            }
            weapon = player.getInventory().getItemInMainHand();
            if (weapon.getType() != Material.TRIDENT) {
                return;
            }
            attacker = player;
            // A riptide spin is routed through Player#attack in 26.2. isRiptiding() is
            // the primary signal; the TRIDENT damage type is a defensive fallback.
            kind = player.isRiptiding()
                    || event.getDamageSource().getDamageType() == DamageType.TRIDENT
                    ? Kind.RIPTIDE : Kind.MELEE;
        } else if (event.getDamager() instanceof Trident trident) {
            if (!(trident.getShooter() instanceof Player player)) {
                return;
            }
            weapon = trident.getItemStack();
            if (weapon.getType() != Material.TRIDENT) {
                return;
            }
            attacker = player;
            kind = Kind.THROWN;
        } else {
            return;
        }

        final Settings settings = this.plugin.settings();
        final int sharpness = weapon.getEnchantmentLevel(Enchantment.SHARPNESS);
        final int fork = forkLevel(weapon);
        final double cooldown = kind == Kind.THROWN ? 1.0 : this.plugin.attackCooldown(attacker);
        final double rawBefore = event.getDamage();

        final double delta;
        if (kind == Kind.THROWN) {
            delta = DamageMath.thrownDelta(sharpness,
                    settings.sharpness().thrownBase(),
                    settings.sharpness().thrownPenaltyPerLevel(),
                    settings.sharpness().thrownMinDamage());
        } else if (kind == Kind.RIPTIDE) {
            // Decision (documented in README): riptide spin attacks are a separate
            // mobility attack. They keep vanilla Sharpness scaling and do not use the
            // 9.0 melee balance table.
            delta = 0.0;
        } else {
            delta = DamageMath.meleeDelta(sharpness, settings.sharpness().headroom(), cooldown);
        }

        final double rawAfter = Math.max(0.0, rawBefore + delta);
        if (rawAfter != rawBefore) {
            event.setDamage(rawAfter);
        }

        this.plugin.debug().damage(attacker, kind, sharpness, fork, cooldown,
                rawBefore, rawAfter, event.getFinalDamage(), target);
    }

    /**
     * The dummy command promises a name that shows the last damage taken. The
     * MONITOR pass runs after every modifier, so getFinalDamage is the number the
     * entity actually loses (before absorption/health clamping).
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDummyDamaged(final EntityDamageEvent event) {
        if (!(event.getEntity() instanceof LivingEntity living)) {
            return;
        }
        if (!this.plugin.isDummy(living)) {
            return;
        }
        living.customName(Component.text(String.format(Locale.ROOT, "%.3f", event.getFinalDamage())));
    }

    private int forkLevel(final ItemStack weapon) {
        final Enchantment fork = this.plugin.forkEnchantment();
        if (fork == null) {
            return 0;
        }
        final ItemMeta meta = weapon.getItemMeta();
        return meta == null ? 0 : meta.getEnchantLevel(fork);
    }
}
