package dev.tridentplus.debug;

import dev.tridentplus.TridentPlusPlugin;
import dev.tridentplus.listener.DamageListener.Kind;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

/**
 * One compact line per trident damage event. The lines are designed to be pasted
 * back into a tuning report.
 */
public final class DebugReporter {

    private final TridentPlusPlugin plugin;
    private final Map<UUID, Boolean> perPlayer = new ConcurrentHashMap<>();

    public DebugReporter(final TridentPlusPlugin plugin) {
        this.plugin = plugin;
    }

    public void set(final Player player, final boolean enabled) {
        this.perPlayer.put(player.getUniqueId(), enabled);
    }

    public boolean isEnabled(final Player player) {
        return this.perPlayer.getOrDefault(player.getUniqueId(), this.plugin.settings().debugDefault());
    }

    public void damage(final Player attacker,
                       final Kind kind,
                       final int sharpnessLevel,
                       final int forkLevel,
                       final double attackCooldown,
                       final double rawBefore,
                       final double rawAfter,
                       final double finalDamage,
                       final LivingEntity target) {
        if (!isEnabled(attacker)) {
            return;
        }
        final String line = String.format(Locale.ROOT,
                "%s sharp=%d fork=%d cd=%.2f raw=%.3f->%.3f final=%.3f target=%s",
                kind, sharpnessLevel, forkLevel, attackCooldown,
                rawBefore, rawAfter, finalDamage, target.getType());
        attacker.sendMessage(Component.text("[TridentPlus] " + line));
        this.plugin.getLogger().info(line);
    }
}
