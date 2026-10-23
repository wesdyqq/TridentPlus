package dev.tridentplus.listener;

import dev.tridentplus.TridentPlusPlugin;
import dev.tridentplus.fork.ForkService;
import io.papermc.paper.event.entity.EntityKnockbackEvent;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

public final class ForkListener implements Listener {

    private final TridentPlusPlugin plugin;

    public ForkListener(final TridentPlusPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * The cooldown is captured before the swing is resolved. Paper fires this event
     * immediately before Player#attack, in the same tick as the damage event.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPreAttack(final PrePlayerAttackEntityEvent event) {
        this.plugin.rememberCooldown(event.getPlayer());
    }

    /**
     * Runs after DamageListener (HIGH) so the primary raw damage already contains
     * the Sharpness compensation.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(final EntityDamageByEntityEvent event) {
        this.plugin.forkService().tryFork(event);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onKnockback(final EntityKnockbackEvent event) {
        final ForkService.SecondaryContext context = ForkService.secondaryContext();
        if (context == null
                || event.getCause() != EntityKnockbackEvent.Cause.ENTITY_ATTACK
                || !event.getEntity().getUniqueId().equals(context.target())) {
            return;
        }
        event.setKnockback(event.getKnockback().multiply(context.knockbackMultiplier()));
    }
}
