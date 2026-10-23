package dev.tridentplus.config;

import dev.tridentplus.balance.DamageMath;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Immutable snapshot of config.yml. The plugin swaps the whole object atomically
 * on reload, so listeners never observe a half-applied configuration.
 */
public record Settings(
        Sharpness sharpness,
        Fork fork,
        Looting looting,
        LootInjection lootInjection,
        ResourcePack resourcePack,
        boolean debugDefault
) {

    public static final String DEFAULT_FORK_DISPLAY_NAME = "Нанизывание";

    public record Sharpness(
            double referenceMaxDamage,
            double capOffset,
            double meleeBase,
            double thrownBase,
            double thrownPenaltyPerLevel,
            double thrownMinDamage
    ) {
        public double cap() {
            return referenceMaxDamage + capOffset;
        }

        public double headroom() {
            return Math.max(0.0, cap() - meleeBase);
        }

        public double meleeDamage(int sharpnessLevel) {
            return meleeBase + DamageMath.tridentMeleeBonus(sharpnessLevel, headroom());
        }

        public double thrownDamage(int sharpnessLevel) {
            return DamageMath.thrownDamage(sharpnessLevel, thrownBase,
                    thrownPenaltyPerLevel, thrownMinDamage);
        }
    }

    public record Fork(
            String displayName,
            List<Double> secondaryDamageRatio,
            double radiusHorizontal,
            double radiusVertical,
            double attackerMaxDistance,
            boolean requireFullCooldown,
            boolean allowOnCrit,
            boolean allowSprintKnockback,
            double secondaryFlatBonus,
            double secondaryKnockbackMultiplier,
            boolean particles,
            boolean sound
    ) {
        public double ratioFor(int level) {
            if (level <= 0) {
                return 0.0;
            }
            if (secondaryDamageRatio != null && level <= secondaryDamageRatio.size()) {
                final Double configured = secondaryDamageRatio.get(level - 1);
                if (configured != null && configured >= 0.0) {
                    return configured;
                }
            }
            return DamageMath.forkRatio(level);
        }
    }

    public record Looting(boolean applyToThrown) {
    }

    public record TableRule(double chance, List<Integer> levelWeights) {
        public double chance() {
            return Math.max(0.0, Math.min(1.0, chance));
        }
    }

    public record LootInjection(boolean enabled, Map<String, TableRule> tables) {
    }

    /**
     * The pack only carries the three Fork translation keys. It is served by the
     * plugin itself unless public-url points at an externally hosted copy of the
     * generated zip.
     */
    public record ResourcePack(
            boolean enabled,
            int port,
            String host,
            String publicUrl,
            String prompt
    ) {
    }

    public static Settings defaults() {
        return new Settings(
                new Sharpness(11.0, 2.0, 9.0, 8.0, 1.0, 1.0),
                new Fork(DEFAULT_FORK_DISPLAY_NAME,
                        List.of(0.5, 0.6667, 0.75),
                        1.5, 0.5, 4.5,
                        true, false, false, 0.0, 0.5, true, true),
                new Looting(true),
                new LootInjection(true, Map.of(
                        "minecraft:chests/shipwreck_treasure", new TableRule(0.10, List.of(60, 30, 10)),
                        "minecraft:chests/shipwreck_supply", new TableRule(0.05, List.of(70, 25, 5)),
                        "minecraft:chests/buried_treasure", new TableRule(0.12, List.of(60, 30, 10))
                )),
                new ResourcePack(true, 8123, "", "",
                        "TridentPlus: примите ресурспак для русского названия зачарования."),
                false
        );
    }

    public static Settings load(final FileConfiguration config) {
        final Settings d = defaults();

        final double referenceMaxDamage = config.getDouble("sharpness.reference-max-damage",
                d.sharpness.referenceMaxDamage);
        final double capOffset = config.getDouble("sharpness.cap-offset",
                d.sharpness.capOffset);
        final double meleeBase = config.getDouble("sharpness.melee-base",
                d.sharpness.meleeBase);
        final double thrownBase = config.getDouble("sharpness.thrown-base",
                d.sharpness.thrownBase);
        final double thrownPenalty = config.getDouble("sharpness.thrown-penalty-per-level",
                d.sharpness.thrownPenaltyPerLevel);
        final double thrownMin = config.getDouble("sharpness.thrown-min-damage",
                d.sharpness.thrownMinDamage);

        final List<Double> ratios = new ArrayList<>();
        for (final double ratio : config.getDoubleList("fork.secondary-damage-ratio")) {
            ratios.add(ratio);
        }
        if (ratios.isEmpty()) {
            ratios.addAll(d.fork.secondaryDamageRatio);
        }

        final Map<String, TableRule> tables = new LinkedHashMap<>();
        final ConfigurationSection section = config.getConfigurationSection("loot-injection.tables");
        if (section != null) {
            for (final String table : section.getKeys(false)) {
                final ConfigurationSection rule = section.getConfigurationSection(table);
                if (rule == null) {
                    continue;
                }
                final List<Integer> weights = new ArrayList<>(rule.getIntegerList("level-weights"));
                if (weights.isEmpty()) {
                    weights.addAll(List.of(60, 30, 10));
                }
                tables.put(table, new TableRule(rule.getDouble("chance", 0.0), List.copyOf(weights)));
            }
        }
        if (tables.isEmpty() && config.getBoolean("loot-injection.enabled", d.lootInjection.enabled)) {
            tables.putAll(d.lootInjection.tables);
        }

        return new Settings(
                new Sharpness(
                        sanitizeNonNegative(referenceMaxDamage, d.sharpness.referenceMaxDamage),
                        sanitizeNonNegative(capOffset, d.sharpness.capOffset),
                        sanitizeNonNegative(meleeBase, d.sharpness.meleeBase),
                        sanitizeNonNegative(thrownBase, d.sharpness.thrownBase),
                        sanitizeNonNegative(thrownPenalty, d.sharpness.thrownPenaltyPerLevel),
                        sanitizeNonNegative(thrownMin, d.sharpness.thrownMinDamage)
                ),
                new Fork(
                        nonBlank(config.getString("fork.display-name", d.fork.displayName),
                                d.fork.displayName),
                        List.copyOf(ratios),
                        sanitizeNonNegative(config.getDouble("fork.radius-horizontal",
                                d.fork.radiusHorizontal), d.fork.radiusHorizontal),
                        sanitizeNonNegative(config.getDouble("fork.radius-vertical",
                                d.fork.radiusVertical), d.fork.radiusVertical),
                        sanitizeNonNegative(config.getDouble("fork.attacker-max-distance",
                                d.fork.attackerMaxDistance), d.fork.attackerMaxDistance),
                        config.getBoolean("fork.require-full-cooldown", d.fork.requireFullCooldown),
                        config.getBoolean("fork.allow-on-crit", d.fork.allowOnCrit),
                        config.getBoolean("fork.allow-sprint-knockback", d.fork.allowSprintKnockback),
                        Math.max(0.0, config.getDouble("fork.secondary-flat-bonus",
                                d.fork.secondaryFlatBonus)),
                        Math.max(0.0, Math.min(2.0, config.getDouble(
                                "fork.secondary-knockback-multiplier",
                                d.fork.secondaryKnockbackMultiplier))),
                        config.getBoolean("fork.particles", d.fork.particles),
                        config.getBoolean("fork.sound", d.fork.sound)
                ),
                new Looting(config.getBoolean("looting.apply-to-thrown", d.looting.applyToThrown)),
                new LootInjection(
                        config.getBoolean("loot-injection.enabled", d.lootInjection.enabled),
                        Map.copyOf(tables)
                ),
                new ResourcePack(
                        config.getBoolean("resource-pack.enabled", d.resourcePack.enabled),
                        sanitizePort(config.getInt("resource-pack.port", d.resourcePack.port),
                                d.resourcePack.port),
                        nonBlank(config.getString("resource-pack.host", d.resourcePack.host),
                                d.resourcePack.host),
                        nonBlank(config.getString("resource-pack.public-url", d.resourcePack.publicUrl),
                                d.resourcePack.publicUrl),
                        nonBlank(config.getString("resource-pack.prompt", d.resourcePack.prompt),
                                d.resourcePack.prompt)
                ),
                config.getBoolean("debug.default", d.debugDefault)
        );
    }

    private static double sanitizeNonNegative(final double value, final double fallback) {
        return Double.isFinite(value) && value >= 0.0 ? value : fallback;
    }

    private static int sanitizePort(final int value, final int fallback) {
        return value >= 0 && value <= 65535 ? value : fallback;
    }

    private static String nonBlank(final String value, final String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
