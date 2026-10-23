package dev.tridentplus;

import io.papermc.paper.registry.TypedKey;
import io.papermc.paper.registry.keys.EnchantmentKeys;
import net.kyori.adventure.key.Key;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;

/**
 * Keys that are shared by the bootstrapper (registry phase) and the running plugin.
 */
public final class TridentPlusKeys {

    public static final String NAMESPACE = "tridentplus";
    public static final String FORK_PATH = "fork";

    /**
     * Client-side translation key shipped in the bundled resource pack
     * (en_us: Fork, ru_ru: Нанизывание, rpr: Многонасажденіе).
     */
    public static final String FORK_TRANSLATION_KEY = "enchantment.tridentplus.fork";

    public static final Key FORK_KEY = Key.key(NAMESPACE, FORK_PATH);
    public static final NamespacedKey FORK_NAMESPACED_KEY =
            new NamespacedKey(NAMESPACE, FORK_PATH);
    public static final TypedKey<Enchantment> FORK = EnchantmentKeys.create(FORK_KEY);

    private TridentPlusKeys() {
    }
}
