package dev.tridentplus.balance;

/**
 * Pure damage arithmetic. No Bukkit imports on purpose: every balance number in
 * the README can be checked by a plain JUnit test.
 *
 * <p>Vanilla 26.2 facts this class is built on (verified against the 26.2 server
 * bytecode during development):
 * <ul>
 *   <li>Sharpness adds {@code 0.5 * level + 0.5} damage.</li>
 *   <li>Melee enchantment damage is multiplied by the attack cooldown scale.</li>
 *   <li>The critical multiplier (1.5x) applies to the weapon base only, not to the
 *       enchantment bonus.</li>
 *   <li>Thrown tridents also run the enchantment damage modifiers, unscaled.</li>
 * </ul>
 */
public final class DamageMath {

    public static final int MAX_SHARPNESS_LEVEL = 5;
    /** 1.5x, see Player#attack in 26.2. */
    public static final double CRIT_MULTIPLIER = 1.5;

    private DamageMath() {
    }

    /** Vanilla Sharpness bonus: I=1.0, II=1.5, III=2.0, IV=2.5, V=3.0. */
    public static double vanillaSharpnessBonus(final int level) {
        if (level <= 0) {
            return 0.0;
        }
        return 0.5 * level + 0.5;
    }

    /** Headroom = cap - melee base. Never hardcoded; both inputs come from config. */
    public static double meleeHeadroom(final double referenceMaxDamage,
                                       final double capOffset,
                                       final double meleeBase) {
        return Math.max(0.0, referenceMaxDamage + capOffset - meleeBase);
    }

    /**
     * k = headroom / vanilla Sharpness V bonus. k > 1 means the trident gains more
     * than vanilla Sharpness per level.
     */
    public static double sharpnessScale(final double headroom) {
        return headroom / vanillaSharpnessBonus(MAX_SHARPNESS_LEVEL);
    }

    /** Trident melee Sharpness bonus before cooldown scaling. */
    public static double tridentMeleeBonus(final int level, final double headroom) {
        if (level <= 0) {
            return 0.0;
        }
        return sharpnessScale(headroom) * vanillaSharpnessBonus(level);
    }

    /** Final melee damage for a trident with the given Sharpness level (full cooldown). */
    public static double tridentMeleeDamage(final double meleeBase,
                                            final int level,
                                            final double headroom) {
        return meleeBase + tridentMeleeBonus(level, headroom);
    }

    /**
     * Amount to add to the event's raw damage. Positive when the trident should
     * gain more than vanilla Sharpness, negative for the thrown penalty.
     */
    public static double meleeDelta(final int level,
                                    final double headroom,
                                    final double attackCooldown) {
        if (level <= 0) {
            return 0.0;
        }
        return (tridentMeleeBonus(level, headroom) - vanillaSharpnessBonus(level))
                * clamp01(attackCooldown);
    }

    /**
     * Thrown trident damage. A penalty of 0 means Sharpness never affects thrown
     * damage. Otherwise each level subtracts the penalty, clamped at the minimum.
     */
    public static double thrownDamage(final int level,
                                      final double thrownBase,
                                      final double penaltyPerLevel,
                                      final double minimumDamage) {
        if (level <= 0) {
            return thrownBase;
        }
        if (penaltyPerLevel <= 0.0) {
            return thrownBase;
        }
        return Math.max(minimumDamage, thrownBase - penaltyPerLevel * level);
    }

    /**
     * Amount to add to a thrown trident's raw damage. Vanilla already added the
     * unscaled Sharpness bonus there, so the delta includes removing it.
     */
    public static double thrownDelta(final int level,
                                     final double thrownBase,
                                     final double penaltyPerLevel,
                                     final double minimumDamage) {
        return thrownDamage(level, thrownBase, penaltyPerLevel, minimumDamage)
                - (thrownBase + vanillaSharpnessBonus(level));
    }

    /** Default Fork ratio L/(L+1): 50%, 66.7%, 75%. */
    public static double forkRatio(final int level) {
        return level <= 0 ? 0.0 : (double) level / (level + 1.0);
    }

    /** Total number of entities hit, including the primary target: 3/4/5. */
    public static int forkTargetCount(final int level) {
        return level <= 0 ? 0 : level + 2;
    }

    /** Damage applied to each secondary target. */
    public static double forkSecondaryDamage(final double primaryNonCritRawDamage,
                                             final double ratio,
                                             final double flatBonus) {
        return Math.max(0.0, primaryNonCritRawDamage * ratio + flatBonus);
    }

    /**
     * Remove the critical multiplier from a raw, already Sharpness-compensated hit.
     * 26.2 multiplies only the weapon base, so the Sharpness contribution is added
     * back unchanged instead of being divided by 1.5.
     */
    public static double withoutCrit(final double compensatedRawDamage,
                                     final double sharpnessContribution,
                                     final boolean critical) {
        if (!critical) {
            return Math.max(0.0, compensatedRawDamage);
        }
        return Math.max(0.0,
                (compensatedRawDamage - sharpnessContribution) / CRIT_MULTIPLIER
                        + sharpnessContribution);
    }

    public static double clamp01(final double value) {
        if (!Double.isFinite(value)) {
            return 1.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }
}
