package dev.projecthim.him.listener;

import dev.projecthim.him.Him;
import dev.projecthim.him.entity.HimBody;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.EntitiesLoadEvent;

/**
 * Keeps HIM's entities under the plugin's control: they cannot be damaged, set on fire, converted
 * or given targets by the game; hits from players are routed to the encounter instead; strays found
 * in freshly loaded chunks are removed.
 */
public final class EntityListener implements Listener {

    private final Him him;

    public EntityListener(Him him) {
        this.him = him;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamage(EntityDamageEvent event) {
        Entity e = event.getEntity();
        if (!him.entities().isOurs(e)) {
            return;
        }
        event.setCancelled(true);
        if (event instanceof EntityDamageByEntityEvent byEntity) {
            Player attacker = attacker(byEntity.getDamager());
            HimBody body = him.entities().bodyOf(e);
            if (attacker != null && body != null) {
                him.events().bodyHit(body, attacker);
            }
        }
    }

    private static Player attacker(Entity damager) {
        if (damager instanceof Player p) {
            return p;
        }
        if (damager instanceof Projectile proj && proj.getShooter() instanceof Player p) {
            return p;
        }
        return null;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCombust(EntityCombustEvent event) {
        if (him.entities().isOurs(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onTransform(EntityTransformEvent event) {
        if (him.entities().isOurs(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onTarget(EntityTargetEvent event) {
        if (him.entities().isOurs(event.getEntity())) {
            event.setCancelled(true);
        } else if (event.getTarget() != null && him.entities().isOurs(event.getTarget())) {
            // Other mobs ignore him.
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (him.entities().isOurs(event.getRightClicked())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        him.entities().sweep(event.getEntities());
    }
}
