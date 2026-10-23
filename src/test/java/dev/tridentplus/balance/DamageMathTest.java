package dev.tridentplus.balance;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every number from the acceptance matrix, with no server involved.
 */
class DamageMathTest {

    private static final double EPS = 1.0e-9;

    // Defaults from config.yml
    private static final double REFERENCE_MAX = 11.0;
    private static final double CAP_OFFSET = 2.0;
    private static final double MELEE_BASE = 9.0;
    private static final double THROWN_BASE = 8.0;
    private static final double THROWN_PENALTY = 1.0;
    private static final double THROWN_MIN = 1.0;
    private static final double HEADROOM =
            DamageMath.meleeHeadroom(REFERENCE_MAX, CAP_OFFSET, MELEE_BASE);

    @Test
    @DisplayName("Vanilla Sharpness bonus is 0.5*L+0.5")
    void vanillaSharpnessBonus() {
        assertEquals(0.0, DamageMath.vanillaSharpnessBonus(0), EPS);
        assertEquals(1.0, DamageMath.vanillaSharpnessBonus(1), EPS);
        assertEquals(1.5, DamageMath.vanillaSharpnessBonus(2), EPS);
        assertEquals(2.0, DamageMath.vanillaSharpnessBonus(3), EPS);
        assertEquals(2.5, DamageMath.vanillaSharpnessBonus(4), EPS);
        assertEquals(3.0, DamageMath.vanillaSharpnessBonus(5), EPS);
    }

    @Test
    @DisplayName("Cap is 13, melee base is 9, headroom is computed as 4")
    void headroomIsComputed() {
        assertEquals(13.0, REFERENCE_MAX + CAP_OFFSET, EPS);
        assertEquals(4.0, HEADROOM, EPS);
        assertEquals(4.0 / 3.0, DamageMath.sharpnessScale(HEADROOM), EPS);
    }

    @Test
    @DisplayName("Trident melee Sharpness bonus: 1.333, 2.0, 2.667, 3.333, 4.0")
    void tridentMeleeBonus() {
        assertEquals(4.0 / 3.0, DamageMath.tridentMeleeBonus(1, HEADROOM), EPS);
        assertEquals(2.0, DamageMath.tridentMeleeBonus(2, HEADROOM), EPS);
        assertEquals(8.0 / 3.0, DamageMath.tridentMeleeBonus(3, HEADROOM), EPS);
        assertEquals(10.0 / 3.0, DamageMath.tridentMeleeBonus(4, HEADROOM), EPS);
        assertEquals(4.0, DamageMath.tridentMeleeBonus(5, HEADROOM), EPS);
    }

    @Test
    @DisplayName("Melee damage 9.0 then 10.333, 11.0, 11.667, 12.333, 13.0")
    void meleeDamage() {
        assertEquals(9.0, DamageMath.tridentMeleeDamage(MELEE_BASE, 0, HEADROOM), EPS);
        assertEquals(10.333333333333334,
                DamageMath.tridentMeleeDamage(MELEE_BASE, 1, HEADROOM), EPS);
        assertEquals(11.0, DamageMath.tridentMeleeDamage(MELEE_BASE, 2, HEADROOM), EPS);
        assertEquals(11.666666666666666,
                DamageMath.tridentMeleeDamage(MELEE_BASE, 3, HEADROOM), EPS);
        assertEquals(12.333333333333334,
                DamageMath.tridentMeleeDamage(MELEE_BASE, 4, HEADROOM), EPS);
        assertEquals(13.0, DamageMath.tridentMeleeDamage(MELEE_BASE, 5, HEADROOM), EPS);
    }

    @Test
    @DisplayName("Melee delta is positive (k > 1) and scales with attack cooldown")
    void meleeDelta() {
        assertEquals(1.0 / 3.0, DamageMath.meleeDelta(1, HEADROOM, 1.0), EPS);
        assertEquals(1.0, DamageMath.meleeDelta(5, HEADROOM, 1.0), EPS);
        assertEquals(0.5, DamageMath.meleeDelta(5, HEADROOM, 0.5), EPS);
        assertEquals(0.0, DamageMath.meleeDelta(0, HEADROOM, 1.0), EPS);
        assertEquals(0.0, DamageMath.meleeDelta(5, HEADROOM, 0.0), EPS);
    }

    @Test
    @DisplayName("A config with no headroom cancels vanilla Sharpness")
    void zeroHeadroom() {
        assertEquals(0.0, DamageMath.meleeHeadroom(9.0, 0.0, 9.0), EPS);
        assertEquals(0.0, DamageMath.tridentMeleeBonus(5, 0.0), EPS);
        // Vanilla still adds 3.0, so the compensation must subtract it.
        assertEquals(-3.0, DamageMath.meleeDelta(5, 0.0, 1.0), EPS);
    }

    @Test
    @DisplayName("Thrown damage: 8 with no Sharpness, then 7, 6, 5, 4, 3")
    void thrownDamage() {
        assertEquals(8.0, DamageMath.thrownDamage(0, THROWN_BASE, THROWN_PENALTY, THROWN_MIN), EPS);
        assertEquals(7.0, DamageMath.thrownDamage(1, THROWN_BASE, THROWN_PENALTY, THROWN_MIN), EPS);
        assertEquals(6.0, DamageMath.thrownDamage(2, THROWN_BASE, THROWN_PENALTY, THROWN_MIN), EPS);
        assertEquals(5.0, DamageMath.thrownDamage(3, THROWN_BASE, THROWN_PENALTY, THROWN_MIN), EPS);
        assertEquals(4.0, DamageMath.thrownDamage(4, THROWN_BASE, THROWN_PENALTY, THROWN_MIN), EPS);
        assertEquals(3.0, DamageMath.thrownDamage(5, THROWN_BASE, THROWN_PENALTY, THROWN_MIN), EPS);
    }

    @Test
    @DisplayName("Penalty 0 keeps thrown damage at 8 at every Sharpness level")
    void thrownPenaltyZero() {
        for (int level = 0; level <= 5; level++) {
            assertEquals(8.0, DamageMath.thrownDamage(level, THROWN_BASE, 0.0, THROWN_MIN), EPS);
            // It must also remove the vanilla Sharpness bonus.
            assertEquals(-DamageMath.vanillaSharpnessBonus(level),
                    DamageMath.thrownDelta(level, THROWN_BASE, 0.0, THROWN_MIN), EPS);
        }
    }

    @Test
    @DisplayName("Thrown damage clamps at thrown-min-damage")
    void thrownClamp() {
        assertEquals(1.0, DamageMath.thrownDamage(5, THROWN_BASE, 10.0, THROWN_MIN), EPS);
    }

    @Test
    @DisplayName("Thrown delta removes vanilla Sharpness and applies the penalty")
    void thrownDelta() {
        // Level 1: desired 7, vanilla total 8 + 1 = 9 -> -2
        assertEquals(-2.0, DamageMath.thrownDelta(1, THROWN_BASE, THROWN_PENALTY, THROWN_MIN), EPS);
        // Level 5: desired 3, vanilla total 8 + 3 = 11 -> -8
        assertEquals(-8.0, DamageMath.thrownDelta(5, THROWN_BASE, THROWN_PENALTY, THROWN_MIN), EPS);
        assertEquals(0.0, DamageMath.thrownDelta(0, THROWN_BASE, THROWN_PENALTY, THROWN_MIN), EPS);
    }

    @Test
    @DisplayName("Fork ratio defaults to L/(L+1)")
    void forkRatio() {
        assertEquals(0.0, DamageMath.forkRatio(0), EPS);
        assertEquals(0.5, DamageMath.forkRatio(1), EPS);
        assertEquals(2.0 / 3.0, DamageMath.forkRatio(2), EPS);
        assertEquals(0.75, DamageMath.forkRatio(3), EPS);
    }

    @Test
    @DisplayName("Fork hits N = L+2 entities in total")
    void forkTargetCount() {
        assertEquals(0, DamageMath.forkTargetCount(0));
        assertEquals(3, DamageMath.forkTargetCount(1));
        assertEquals(4, DamageMath.forkTargetCount(2));
        assertEquals(5, DamageMath.forkTargetCount(3));
    }

    @Test
    @DisplayName("Fork secondary damage without Sharpness: 4.5 / 6.0 / 6.75")
    void forkSecondaryWithoutSharpness() {
        assertEquals(4.5, DamageMath.forkSecondaryDamage(9.0, DamageMath.forkRatio(1), 0.0), EPS);
        assertEquals(6.0, DamageMath.forkSecondaryDamage(9.0, DamageMath.forkRatio(2), 0.0), EPS);
        assertEquals(6.75, DamageMath.forkSecondaryDamage(9.0, DamageMath.forkRatio(3), 0.0), EPS);
    }

    @Test
    @DisplayName("Fork secondary with Sharpness V: 6.5 / 8.667 / 9.75")
    void forkSecondaryWithSharpnessV() {
        assertEquals(6.5, DamageMath.forkSecondaryDamage(13.0, DamageMath.forkRatio(1), 0.0), EPS);
        assertEquals(8.666666666666666,
                DamageMath.forkSecondaryDamage(13.0, DamageMath.forkRatio(2), 0.0), EPS);
        assertEquals(9.75, DamageMath.forkSecondaryDamage(13.0, DamageMath.forkRatio(3), 0.0), EPS);
    }

    @Test
    @DisplayName("The config sample ratio 0.6667 rounds to the acceptance numbers")
    void configuredRatio() {
        assertEquals(6.0003, DamageMath.forkSecondaryDamage(9.0, 0.6667, 0.0), 1.0e-9);
        assertEquals(8.6671, DamageMath.forkSecondaryDamage(13.0, 0.6667, 0.0), 1.0e-9);
        // Displayed to three decimals, both match the acceptance matrix.
        assertEquals(6.000, Math.round(
                DamageMath.forkSecondaryDamage(9.0, 0.6667, 0.0) * 1000.0) / 1000.0, EPS);
        assertEquals(8.667, Math.round(
                DamageMath.forkSecondaryDamage(13.0, 0.6667, 0.0) * 1000.0) / 1000.0, EPS);
    }

    @Test
    @DisplayName("Flat Fork bonus is added after the ratio")
    void forkFlatBonus() {
        assertEquals(5.5, DamageMath.forkSecondaryDamage(9.0, 0.5, 1.0), EPS);
        assertEquals(0.0, DamageMath.forkSecondaryDamage(0.0, 0.5, 0.0), EPS);
    }

    @Test
    @DisplayName("Removing crit divides only the base, not the Sharpness bonus")
    void withoutCrit() {
        // Base 9 * 1.5 = 13.5; Sharpness contribution 4 -> raw 17.5.
        assertEquals(13.0, DamageMath.withoutCrit(17.5, 4.0, true), EPS);
        // No Sharpness: 13.5 -> 9.0.
        assertEquals(9.0, DamageMath.withoutCrit(13.5, 0.0, true), EPS);
        // Not a crit: unchanged.
        assertEquals(13.0, DamageMath.withoutCrit(13.0, 4.0, false), EPS);
    }

    @Test
    @DisplayName("Impaling stays vanilla: +12.5 at level V on top of the melee result")
    void impalingIsVanillaAndAdditive() {
        // This plugin never subtracts or adds Impaling; the number is recorded here so
        // the acceptance matrix is represented in the test suite too.
        final double vanillaImpalingV = 12.5;
        assertEquals(25.5,
                DamageMath.tridentMeleeDamage(MELEE_BASE, 5, HEADROOM) + vanillaImpalingV,
                EPS);
    }

    @Test
    @DisplayName("Cooldown values are clamped to 0..1")
    void clampCooldown() {
        assertEquals(1.0, DamageMath.clamp01(1.3), EPS);
        assertEquals(0.0, DamageMath.clamp01(-0.2), EPS);
        assertEquals(0.75, DamageMath.clamp01(0.75), EPS);
    }
}
