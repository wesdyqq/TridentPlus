package dev.tridentplus;

import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEventManager;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import io.papermc.paper.registry.RegistryKey;
import io.papermc.paper.registry.TypedKey;
import io.papermc.paper.registry.data.EnchantmentRegistryEntry;
import io.papermc.paper.registry.event.RegistryEvents;
import io.papermc.paper.registry.keys.EnchantmentKeys;
import io.papermc.paper.registry.keys.ItemTypeKeys;
import io.papermc.paper.registry.keys.tags.EnchantmentTagKeys;
import io.papermc.paper.registry.keys.tags.ItemTypeTagKeys;
import io.papermc.paper.registry.set.RegistryKeySet;
import io.papermc.paper.registry.set.RegistrySet;
import io.papermc.paper.tag.PostFlattenTagRegistrar;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.kyori.adventure.text.Component;
import org.bukkit.Keyed;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemType;

/**
 * Registry changes are only possible during the bootstrap phase, which is why this
 * plugin is a Paper plugin with a bootstrapper and why /reload cannot re-apply them.
 */
public final class TridentPlusBootstrap implements PluginBootstrap {

    /** Vanilla 26.2 Sharpness: supported #enchantable/sharp_weapon, primary #enchantable/melee_weapon. */
    private static final List<TypedKey<ItemType>> SHARP_WEAPON_FALLBACK = List.of(
            ItemTypeKeys.WOODEN_SWORD, ItemTypeKeys.STONE_SWORD, ItemTypeKeys.COPPER_SWORD,
            ItemTypeKeys.IRON_SWORD, ItemTypeKeys.GOLDEN_SWORD, ItemTypeKeys.DIAMOND_SWORD,
            ItemTypeKeys.NETHERITE_SWORD,
            ItemTypeKeys.WOODEN_AXE, ItemTypeKeys.STONE_AXE, ItemTypeKeys.COPPER_AXE,
            ItemTypeKeys.IRON_AXE, ItemTypeKeys.GOLDEN_AXE, ItemTypeKeys.DIAMOND_AXE,
            ItemTypeKeys.NETHERITE_AXE,
            ItemTypeKeys.WOODEN_SPEAR, ItemTypeKeys.STONE_SPEAR, ItemTypeKeys.COPPER_SPEAR,
            ItemTypeKeys.IRON_SPEAR, ItemTypeKeys.GOLDEN_SPEAR, ItemTypeKeys.DIAMOND_SPEAR,
            ItemTypeKeys.NETHERITE_SPEAR
    );

    /** #enchantable/melee_weapon in 26.2: swords + spears (no axes). */
    private static final List<TypedKey<ItemType>> MELEE_WEAPON_FALLBACK = List.of(
            ItemTypeKeys.WOODEN_SWORD, ItemTypeKeys.STONE_SWORD, ItemTypeKeys.COPPER_SWORD,
            ItemTypeKeys.IRON_SWORD, ItemTypeKeys.GOLDEN_SWORD, ItemTypeKeys.DIAMOND_SWORD,
            ItemTypeKeys.NETHERITE_SWORD,
            ItemTypeKeys.WOODEN_SPEAR, ItemTypeKeys.STONE_SPEAR, ItemTypeKeys.COPPER_SPEAR,
            ItemTypeKeys.IRON_SPEAR, ItemTypeKeys.GOLDEN_SPEAR, ItemTypeKeys.DIAMOND_SPEAR,
            ItemTypeKeys.NETHERITE_SPEAR
    );

    /**
     * 26.2 #exclusive_set/damage, without the entry we are deliberately allowing.
     * Kept explicit so that a failed read cannot silently clear vanilla exclusivity.
     */
    private static final List<TypedKey<Enchantment>> DAMAGE_EXCLUSIVE_FALLBACK = List.of(
            EnchantmentKeys.SHARPNESS,
            EnchantmentKeys.SMITE,
            EnchantmentKeys.BANE_OF_ARTHROPODS,
            EnchantmentKeys.IMPALING,
            EnchantmentKeys.DENSITY,
            EnchantmentKeys.BREACH
    );

    @Override
    public void bootstrap(final BootstrapContext context) {
        final String forkDisplayName = BootstrapConfig.loadForkDisplayName(context);
        final LifecycleEventManager<BootstrapContext> manager = context.getLifecycleManager();

        manager.registerEventHandler(RegistryEvents.ENCHANTMENT.entryAdd().newHandler(event -> {
            if (event.key().equals(EnchantmentKeys.SHARPNESS)) {
                patchSharpness(event.builder(), context);
            } else if (event.key().equals(EnchantmentKeys.LOOTING)) {
                patchLooting(event.builder(), context);
            } else if (event.key().equals(EnchantmentKeys.IMPALING)) {
                patchImpaling(event.builder(), context);
            }
        }));

        manager.registerEventHandler(RegistryEvents.ENCHANTMENT.compose(), event ->
                event.registry().register(TridentPlusKeys.FORK, builder -> builder
                        // Translatable key resolved from the bundled resource pack;
                        // fallback keeps a readable name if the pack is declined.
                        .description(Component.translatable(TridentPlusKeys.FORK_TRANSLATION_KEY)
                                .fallback(forkDisplayName))
                        .supportedItems(event.getOrCreateTag(ItemTypeTagKeys.ENCHANTABLE_TRIDENT))
                        .primaryItems(event.getOrCreateTag(ItemTypeTagKeys.ENCHANTABLE_TRIDENT))
                        .weight(2)
                        .maxLevel(3)
                        .minimumCost(EnchantmentRegistryEntry.EnchantmentCost.of(5, 9))
                        .maximumCost(EnchantmentRegistryEntry.EnchantmentCost.of(20, 9))
                        .anvilCost(4)
                        .activeSlots(EquipmentSlotGroup.MAINHAND)
                ));

        manager.registerEventHandler(
                LifecycleEvents.TAGS.postFlatten(RegistryKey.ENCHANTMENT),
                event -> {
                    final PostFlattenTagRegistrar<Enchantment> registrar = event.registrar();
                    // Acquisition mirrors a normal non-treasure enchantment: table, librarian
                    // books and random loot. Deliberately not treasure and not a curse.
                    registrar.addToTag(EnchantmentTagKeys.IN_ENCHANTING_TABLE, Set.of(TridentPlusKeys.FORK));
                    registrar.addToTag(EnchantmentTagKeys.TRADEABLE, Set.of(TridentPlusKeys.FORK));
                    registrar.addToTag(EnchantmentTagKeys.ON_RANDOM_LOOT, Set.of(TridentPlusKeys.FORK));
                });
    }

    private static void patchSharpness(final EnchantmentRegistryEntry.Builder builder,
                                       final BootstrapContext context) {
        final RegistryKeySet<ItemType> supported = addTrident(
                builder.supportedItems(), SHARP_WEAPON_FALLBACK, "Sharpness supported", context);
        final RegistryKeySet<ItemType> primary = addTrident(
                builder.primaryItems(), MELEE_WEAPON_FALLBACK, "Sharpness primary", context);

        // Vanilla Sharpness and Impaling are both in #exclusive_set/damage. The spec
        // wants them compatible, so rebuild the set without Impaling. Smite/Bane/etc.
        // stay exclusive.
        final RegistryKeySet<Enchantment> exclusive = without(
                builder.exclusiveWith(), EnchantmentKeys.SHARPNESS, EnchantmentKeys.IMPALING,
                DAMAGE_EXCLUSIVE_FALLBACK, "Sharpness exclusive", context);

        builder.supportedItems(supported).primaryItems(primary).exclusiveWith(exclusive);
    }

    private static void patchLooting(final EnchantmentRegistryEntry.Builder builder,
                                     final BootstrapContext context) {
        final RegistryKeySet<ItemType> supported = addTrident(
                builder.supportedItems(), MELEE_WEAPON_FALLBACK, "Looting supported", context);
        // Vanilla Looting has no primary_items (null means "same as supported"). Setting
        // it explicitly adds the trident to both sets and keeps the existing behaviour.
        final RegistryKeySet<ItemType> primary = builder.primaryItems() == null
                ? supported
                : addTrident(builder.primaryItems(), MELEE_WEAPON_FALLBACK, "Looting primary", context);
        builder.supportedItems(supported).primaryItems(primary);
    }

    private static void patchImpaling(final EnchantmentRegistryEntry.Builder builder,
                                      final BootstrapContext context) {
        // Remove Sharpness from Impaling's side of the conflict as well; the exclusive
        // set is directional in the data and both directions must agree.
        builder.exclusiveWith(without(
                builder.exclusiveWith(), EnchantmentKeys.IMPALING, EnchantmentKeys.SHARPNESS,
                DAMAGE_EXCLUSIVE_FALLBACK, "Impaling exclusive", context));
    }

    /**
     * Preserve the existing set and add the trident. If the builder's set cannot be
     * resolved during entryAdd (it may still be an unresolved tag), rebuild it from
     * the vanilla item list instead of dropping all existing items.
     */
    private static RegistryKeySet<ItemType> addTrident(final RegistryKeySet<ItemType> existing,
                                                       final List<TypedKey<ItemType>> fallback,
                                                       final String label,
                                                       final BootstrapContext context) {
        final LinkedHashSet<TypedKey<ItemType>> keys = readKeys(existing, label, context);
        final boolean readExisting = !keys.isEmpty();
        if (!readExisting) {
            keys.addAll(fallback);
        }
        keys.add(ItemTypeKeys.TRIDENT);
        context.getLogger().info("{}: {} ({})",
                label,
                readExisting ? "kept existing set and added trident" : "rebuilt from vanilla fallback and added trident",
                keys.size());
        return RegistrySet.keySet(RegistryKey.ITEM, keys);
    }

    private static RegistryKeySet<Enchantment> without(final RegistryKeySet<Enchantment> existing,
                                                       final TypedKey<Enchantment> self,
                                                       final TypedKey<Enchantment> remove,
                                                       final List<TypedKey<Enchantment>> fallback,
                                                       final String label,
                                                       final BootstrapContext context) {
        final LinkedHashSet<TypedKey<Enchantment>> keys = readKeys(existing, label, context);
        final boolean readExisting = !keys.isEmpty();
        if (!readExisting) {
            keys.addAll(fallback);
        }
        keys.remove(self);
        keys.remove(remove);
        context.getLogger().info("{}: {} ({} entries)",
                label,
                readExisting ? "filtered existing set" : "rebuilt from vanilla fallback",
                keys.size());
        return RegistrySet.keySet(RegistryKey.ENCHANTMENT, keys);
    }

    private static <T extends Keyed> LinkedHashSet<TypedKey<T>> readKeys(final RegistryKeySet<T> set,
                                                          final String label,
                                                          final BootstrapContext context) {
        final LinkedHashSet<TypedKey<T>> keys = new LinkedHashSet<>();
        if (set == null) {
            return keys;
        }
        try {
            keys.addAll(set.values());
        } catch (final RuntimeException ex) {
            // A tag that has not been bound yet is the expected failure mode here.
            // The caller logs the rebuild at info level; keep the raw exception at
            // debug level so startup does not look alarming.
            context.getLogger().debug("{}: could not read existing registry set ({})",
                    label, ex.getMessage());
        }
        return keys;
    }
}
