package dev.tridentplus.debug;

import dev.tridentplus.TridentPlusKeys;
import dev.tridentplus.TridentPlusPlugin;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import io.papermc.paper.registry.keys.tags.EnchantmentTagKeys;
import io.papermc.paper.registry.tag.Tag;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.TranslatableComponent;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;

/**
 * Proves that the registry patches were actually applied. Runs on startup and
 * from /tridentplus selfcheck.
 */
public final class SelfCheck {

    public record Result(boolean passed, List<String> lines) {
    }

    private final TridentPlusPlugin plugin;
    private final List<String> lines = new ArrayList<>();
    private boolean passed = true;

    public SelfCheck(final TridentPlusPlugin plugin) {
        this.plugin = plugin;
    }

    public Result run() {
        checkFork();
        checkForkTags();
        checkSharpnessAndLootingOnTrident();
        checkSharpnessImpalingCompatibility();
        checkSharpnessCompatibility();
        checkSharpnessStillSupportsVanillaWeapons();
        checkRiptideExclusivity();
        checkSweepingEdgeUnchanged();
        checkForkCompatibility();
        return new Result(this.passed, List.copyOf(this.lines));
    }

    private void checkFork() {
        final Enchantment fork = this.plugin.forkEnchantment();
        check("Fork is registered", fork != null);
        if (fork == null) {
            return;
        }
        check("Fork maxLevel == 3 (got " + fork.getMaxLevel() + ")", fork.getMaxLevel() == 3);
        check("Fork applies to trident", fork.canEnchantItem(new ItemStack(Material.TRIDENT)));
        check("Fork does not apply to sword", !fork.canEnchantItem(new ItemStack(Material.NETHERITE_SWORD)));
        check("Fork description is a translatable component",
                fork.description() instanceof TranslatableComponent translatable
                        && TridentPlusKeys.FORK_TRANSLATION_KEY.equals(translatable.key()));
        check("Fork translation has a readable fallback",
                fork.description() instanceof TranslatableComponent translatable
                        && translatable.fallback() != null
                        && !translatable.fallback().isBlank());
    }

    private void checkForkTags() {
        final Tag<Enchantment> enchanting = tag(EnchantmentTagKeys.IN_ENCHANTING_TABLE);
        final Tag<Enchantment> tradeable = tag(EnchantmentTagKeys.TRADEABLE);
        final Tag<Enchantment> randomLoot = tag(EnchantmentTagKeys.ON_RANDOM_LOOT);
        final Tag<Enchantment> treasure = tag(EnchantmentTagKeys.TREASURE);
        final Tag<Enchantment> curse = tag(EnchantmentTagKeys.CURSE);
        check("Fork in #in_enchanting_table", contains(enchanting));
        check("Fork in #tradeable", contains(tradeable));
        check("Fork in #on_random_loot", contains(randomLoot));
        check("Fork not in #treasure", !contains(treasure));
        check("Fork not in #curse", !contains(curse));
    }

    private void checkSharpnessAndLootingOnTrident() {
        final Enchantment sharpness = Enchantment.SHARPNESS;
        final Enchantment looting = Enchantment.LOOTING;
        check("Sharpness can enchant trident",
                sharpness.canEnchantItem(new ItemStack(Material.TRIDENT)));
        check("Looting can enchant trident",
                looting.canEnchantItem(new ItemStack(Material.TRIDENT)));
    }

    private void checkSharpnessImpalingCompatibility() {
        final Enchantment sharpness = Enchantment.SHARPNESS;
        final Enchantment impaling = Enchantment.IMPALING;
        check("Sharpness does not conflict with Impaling", !sharpness.conflictsWith(impaling));
        check("Impaling does not conflict with Sharpness", !impaling.conflictsWith(sharpness));
        check("Sharpness still conflicts with Smite",
                sharpness.conflictsWith(Enchantment.SMITE));
        check("Sharpness still conflicts with Bane of Arthropods",
                sharpness.conflictsWith(Enchantment.BANE_OF_ARTHROPODS));
    }

    private void checkSharpnessCompatibility() {
        final Enchantment sharpness = Enchantment.SHARPNESS;
        final Enchantment[] compatible = {
                Enchantment.IMPALING, Enchantment.LOYALTY, Enchantment.RIPTIDE,
                Enchantment.CHANNELING, Enchantment.UNBREAKING, Enchantment.MENDING,
                Enchantment.LOOTING
        };
        boolean all = true;
        for (final Enchantment other : compatible) {
            all &= !sharpness.conflictsWith(other) && !other.conflictsWith(sharpness);
        }
        check("Sharpness compatible with the listed trident enchantments", all);
    }

    private void checkSharpnessStillSupportsVanillaWeapons() {
        final Enchantment sharpness = Enchantment.SHARPNESS;
        check("Sharpness still accepts sword",
                sharpness.canEnchantItem(new ItemStack(Material.NETHERITE_SWORD)));
        check("Sharpness still accepts axe",
                sharpness.canEnchantItem(new ItemStack(Material.NETHERITE_AXE)));
        check("Sharpness still accepts spear",
                sharpness.canEnchantItem(new ItemStack(Material.IRON_SPEAR)));
    }

    private void checkRiptideExclusivity() {
        check("Riptide still conflicts with Loyalty",
                Enchantment.RIPTIDE.conflictsWith(Enchantment.LOYALTY));
        check("Riptide still conflicts with Channeling",
                Enchantment.RIPTIDE.conflictsWith(Enchantment.CHANNELING));
    }

    private void checkSweepingEdgeUnchanged() {
        check("Sweeping Edge still accepts sword",
                Enchantment.SWEEPING_EDGE.canEnchantItem(new ItemStack(Material.NETHERITE_SWORD)));
    }

    private void checkForkCompatibility() {
        final Enchantment fork = this.plugin.forkEnchantment();
        if (fork == null) {
            return;
        }
        final Enchantment[] compatible = {
                Enchantment.SHARPNESS, Enchantment.IMPALING, Enchantment.LOYALTY,
                Enchantment.CHANNELING, Enchantment.RIPTIDE, Enchantment.UNBREAKING,
                Enchantment.MENDING, Enchantment.LOOTING
        };
        boolean all = true;
        for (final Enchantment other : compatible) {
            all &= !fork.conflictsWith(other) && !other.conflictsWith(fork);
        }
        check("Fork compatible with all trident enchantments", all);
        check("Sharpness compatible with Impaling (reverse check)",
                !Enchantment.IMPALING.conflictsWith(Enchantment.SHARPNESS));
        check("Sharpness supported items still contain trident",
                Enchantment.SHARPNESS.getSupportedItems().contains(
                        io.papermc.paper.registry.keys.ItemTypeKeys.TRIDENT));
        check("Fork supported set is trident-only",
                fork.getSupportedItems().size() == 1);
        check("Fork primary set is trident-only",
                fork.getPrimaryItems() != null && fork.getPrimaryItems().size() == 1);
    }

    private Tag<Enchantment> tag(final io.papermc.paper.registry.tag.TagKey<Enchantment> key) {
        try {
            return RegistryAccess.registryAccess()
                    .getRegistry(RegistryKey.ENCHANTMENT)
                    .getTag(key);
        } catch (final RuntimeException ex) {
            return null;
        }
    }

    private static boolean contains(final Tag<Enchantment> tag) {
        return tag != null && tag.values().contains(TridentPlusKeys.FORK);
    }

    private void check(final String description, final boolean condition) {
        if (!condition) {
            this.passed = false;
        }
        this.lines.add((condition ? "PASS " : "FAIL ") + description);
    }
}
