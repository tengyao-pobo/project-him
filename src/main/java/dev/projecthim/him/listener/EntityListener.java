package dev.projecthim.him.listener;

import dev.projecthim.him.Him;
import dev.projecthim.him.entity.HimBody;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
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
        if (event instanceof EntityDamageByEntityEvent byEntity) {
            Player attacker = attacker(byEntity.getDamager());
            HimBody body = him.entities().bodyOf(e);
            if (attacker != null && body != null && body.isViewer(attacker)) {
                double raw = event.getDamage();
                // Not cancelled: the game plays a real hit (red flash, crit and sweep effects, the hit
                // sounds) instead of the "no damage" of an invulnerable target. The body itself takes a
                // token amount; HIM keeps his own health.
                event.setDamage(0.01);
                him.events().bodyHit(body, attacker, raw);
                return;
            }
        }
        event.setCancelled(true);
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
        UUID minionOf = him.entities().tags().minionTarget(event.getEntity());
        if (minionOf != null) {
            // His summoned monsters fight only the hunted player.
            if (event.getTarget() != null && !event.getTarget().getUniqueId().equals(minionOf)) {
                event.setCancelled(true);
            }
            return;
        }
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

    /** His arrows only ever hurt the player they were shot at, for the configured damage. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onProjectileDamage(EntityDamageByEntityEvent event) {
        UUID target = him.entities().tags().projectileTarget(event.getDamager());
        if (target == null) {
            return;
        }
        if (!event.getEntity().getUniqueId().equals(target)) {
            event.setCancelled(true);
            return;
        }
        event.setDamage(event.getDamager() instanceof org.bukkit.entity.Trident
                ? him.config().chase().water().tridentDamage() : him.config().chase().weapons().arrowDamage());
    }

    /** Only someone who can see him can hit him; everything else passes through. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onProjectileHitHim(ProjectileHitEvent event) {
        Entity hit = event.getHitEntity();
        if (hit == null || !him.entities().isOurs(hit)) {
            return;
        }
        HimBody body = him.entities().bodyOf(hit);
        Player shooter = event.getEntity().getShooter() instanceof Player p ? p : null;
        if (body == null || shooter == null || !body.isViewer(shooter)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onProjectileHit(ProjectileHitEvent event) {
        Projectile projectile = event.getEntity();
        UUID target = him.entities().tags().projectileTarget(projectile);
        if (target == null) {
            return;
        }
        Entity hit = event.getHitEntity();
        if (hit instanceof org.bukkit.entity.Boat boat
                && boat.getPassengers().stream().anyMatch(e -> e.getUniqueId().equals(target))) {
            // The boat the hunted player sits in: it breaks.
            if (him.config().chase().water().sinkBoats()) {
                org.bukkit.Material item = boat.getBoatMaterial();
                org.bukkit.Location at = boat.getLocation();
                boat.eject();
                boat.remove();
                if (item != null && item.isItem()) {
                    at.getWorld().dropItemNaturally(at, new org.bukkit.inventory.ItemStack(item));
                }
            }
            Bukkit.getScheduler().runTaskLater(him.plugin(), projectile::remove, 1L);
            return;
        }
        if (hit != null && !hit.getUniqueId().equals(target)) {
            // Passes through anyone else.
            event.setCancelled(true);
            return;
        }
        // Nothing of his stays behind in the world.
        Bukkit.getScheduler().runTaskLater(him.plugin(), projectile::remove, hit == null ? 30L : 1L);
    }

    /** His splash potions only affect the player they were thrown at. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPotionSplash(PotionSplashEvent event) {
        UUID target = him.entities().tags().projectileTarget(event.getEntity());
        if (target == null) {
            return;
        }
        for (LivingEntity e : event.getAffectedEntities()) {
            if (!e.getUniqueId().equals(target)) {
                event.setIntensity(e, 0);
            }
        }
    }

    /** Nothing to farm from his monsters. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onMinionDeath(EntityDeathEvent event) {
        if (him.entities().tags().minionTarget(event.getEntity()) != null) {
            event.getDrops().clear();
            event.setDroppedExp(0);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        him.entities().sweep(event.getEntities());
    }
}
