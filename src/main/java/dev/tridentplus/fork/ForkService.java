package dev.tridentplus.fork;

import dev.tridentplus.TridentPlusPlugin;
import dev.tridentplus.balance.DamageMath;
import dev.tridentplus.config.Settings;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Marker;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scoreboard.Team;

/**
 * Melee-only Fork implementation. Thrown Fork is intentionally not enabled yet;
 * {@link #secondaryDamage} and {@link #eligible} are the extension points for it.
 */
public final class ForkService {

    /**
     * Secondary hits run synchronously through LivingEntity#damage, which fires the
     * damage event again. This thread-local guard prevents Fork and Sharpness
     * compensation from re-entering on our own damage.
     */
    private static final ThreadLocal<SecondaryContext> SECONDARY = new ThreadLocal<>();

    public record SecondaryContext(
            Player attacker,
            UUID target,
            int sharpnessLevel,
            int forkLevel,
            double attackCooldown,
            double knockbackMultiplier
    ) {
    }

    private final TridentPlusPlugin plugin;

    public ForkService(final TridentPlusPlugin plugin) {
        this.plugin = plugin;
    }

    public static boolean isSecondaryHit() {
        return SECONDARY.get() != null;
    }

    public static SecondaryContext secondaryContext() {
        return SECONDARY.get();
    }

    /** Called from the damage listener after Sharpness compensation has been applied. */
    public void tryFork(final EntityDamageByEntityEvent event) {
        if (isSecondaryHit() || event.isCancelled()) {
            return;
        }
        if (!(event.getDamager() instanceof Player attacker)) {
            return;
        }
        if (event.getCause() != org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_ATTACK) {
            return;
        }
        if (!(event.getEntity() instanceof LivingEntity primary)) {
            return;
        }
        // Riptide spin attacks go through the melee path in 26.2 but are not a
        // "real swing" for Fork purposes.
        if (attacker.isRiptiding()) {
            return;
        }

        final ItemStack weapon = attacker.getInventory().getItemInMainHand();
        if (weapon.getType() != org.bukkit.Material.TRIDENT) {
            return;
        }
        final int forkLevel = forkLevel(weapon);
        if (forkLevel <= 0) {
            return;
        }

        final Settings settings = this.plugin.settings();
        final double cooldown = this.plugin.attackCooldown(attacker);
        if (settings.fork().requireFullCooldown() && cooldown < 0.9) {
            return;
        }
        // Mirrors vanilla sweep: a sprint hit at full charge is the knockback attack,
        // not a sweep.
        if (!settings.fork().allowSprintKnockback() && attacker.isSprinting() && cooldown >= 0.9) {
            return;
        }
        if (!settings.fork().allowOnCrit() && event.isCritical()) {
            return;
        }

        final int sharpnessLevel = weapon.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.SHARPNESS);
        final double sharpnessContribution = DamageMath.tridentMeleeBonus(
                sharpnessLevel, settings.sharpness().headroom()) * cooldown;
        final double primaryNonCritRaw = DamageMath.withoutCrit(
                event.getDamage(), sharpnessContribution, event.isCritical());

        final int totalTargets = DamageMath.forkTargetCount(forkLevel);
        final List<LivingEntity> candidates = findCandidates(attacker, primary, settings);
        final int secondaryCount = Math.min(totalTargets - 1, candidates.size());
        if (secondaryCount <= 0) {
            return;
        }

        final double ratio = settings.fork().ratioFor(forkLevel);
        final double damage = DamageMath.forkSecondaryDamage(primaryNonCritRaw, ratio,
                settings.fork().secondaryFlatBonus());
        if (damage <= 0.0) {
            return;
        }

        for (int i = 0; i < secondaryCount; i++) {
            final LivingEntity target = candidates.get(i);
            applySecondary(attacker, target, damage, sharpnessLevel, forkLevel, cooldown,
                    settings.fork().secondaryKnockbackMultiplier());
            if (settings.fork().particles()) {
                target.getWorld().spawnParticle(Particle.SWEEP_ATTACK,
                        target.getLocation().add(0.0, target.getHeight() * 0.5, 0.0),
                        1, 0.0, 0.0, 0.0, 0.0);
            }
            if (settings.fork().sound() && i == 0) {
                target.getWorld().playSound(target.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP,
                        0.8f, 1.0f);
            }
        }
    }

    private void applySecondary(final Player attacker,
                                final LivingEntity target,
                                final double damage,
                                final int sharpnessLevel,
                                final int forkLevel,
                                final double cooldown,
                                final double knockbackMultiplier) {
        final DamageSource source = DamageSource.builder(DamageType.PLAYER_ATTACK)
                .withCausingEntity(attacker)
                .withDirectEntity(attacker)
                .withDamageLocation(target.getLocation())
                .build();
        final SecondaryContext context = new SecondaryContext(
                attacker, target.getUniqueId(), sharpnessLevel, forkLevel, cooldown, knockbackMultiplier);
        SECONDARY.set(context);
        try {
            target.damage(damage, source);
        } finally {
            SECONDARY.remove();
        }
    }

    private List<LivingEntity> findCandidates(final Player attacker,
                                              final LivingEntity primary,
                                              final Settings settings) {
        final double radiusH = settings.fork().radiusHorizontal();
        final double radiusV = settings.fork().radiusVertical();
        final double maxDistance = settings.fork().attackerMaxDistance();
        final double maxDistanceSq = maxDistance * maxDistance;

        final List<LivingEntity> result = new ArrayList<>();
        // A small margin compensates for getNearbyEntities using bounding boxes.
        for (final Entity nearby : primary.getNearbyEntities(radiusH + 1.0, radiusV + 1.0, radiusH + 1.0)) {
            if (!(nearby instanceof LivingEntity candidate)) {
                continue;
            }
            if (!eligible(attacker, primary, candidate, maxDistanceSq)) {
                continue;
            }
            final double dx = candidate.getX() - primary.getX();
            final double dz = candidate.getZ() - primary.getZ();
            if (dx * dx + dz * dz > radiusH * radiusH) {
                continue;
            }
            if (Math.abs(candidate.getY() - primary.getY()) > radiusV) {
                continue;
            }
            result.add(candidate);
        }
        result.sort(Comparator.comparingDouble(candidate ->
                candidate.getLocation().distanceSquared(primary.getLocation())));
        return result;
    }

    private boolean eligible(final Player attacker,
                             final LivingEntity primary,
                             final LivingEntity candidate,
                             final double maxDistanceSq) {
        if (candidate.equals(attacker) || candidate.equals(primary)) {
            return false;
        }
        if (candidate.isDead() || candidate.getHealth() <= 0.0 || candidate.isInvulnerable()) {
            return false;
        }
        if (candidate instanceof ArmorStand
                || candidate instanceof Marker
                || candidate instanceof Interaction
                || candidate instanceof Display
                || candidate instanceof ItemFrame) {
            return false;
        }
        if (candidate instanceof Tameable tameable
                && tameable.isTamed()
                && attacker.getUniqueId().equals(tameable.getOwnerUniqueId())) {
            return false;
        }
        final Entity vehicle = attacker.getVehicle();
        if (vehicle != null && vehicle.equals(candidate)) {
            return false;
        }
        if (candidate instanceof Player other) {
            if (other.getGameMode() == GameMode.SPECTATOR) {
                return false;
            }
            final World world = other.getWorld();
            if (!world.getGameRuleValue(org.bukkit.GameRules.PVP) && !other.equals(attacker)) {
                return false;
            }
            // Compare both entries on the attacker's scoreboard so a per-player
            // scoreboard cannot make two different teams look unrelated.
            final Team attackerTeam = attacker.getScoreboard().getEntryTeam(attacker.getName());
            final Team targetTeam = attacker.getScoreboard().getEntryTeam(other.getName());
            if (attackerTeam != null && attackerTeam.equals(targetTeam)
                    && !attackerTeam.allowFriendlyFire()) {
                return false;
            }
        }
        final Location attackerLocation = attacker.getLocation();
        return attackerLocation.distanceSquared(candidate.getLocation()) <= maxDistanceSq;
    }

    private int forkLevel(final ItemStack weapon) {
        final var fork = this.plugin.forkEnchantment();
        if (fork == null) {
            return 0;
        }
        final ItemMeta meta = weapon.getItemMeta();
        if (meta == null || !meta.hasEnchant(fork)) {
            return 0;
        }
        return meta.getEnchantLevel(fork);
    }
}
