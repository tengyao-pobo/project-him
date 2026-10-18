package dev.projecthim.him.entity;

import com.destroystokyo.paper.SkinParts;
import dev.projecthim.him.platform.EntityTags;
import dev.projecthim.him.platform.SkinService;
import dev.projecthim.him.util.MathUtil;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.inventory.MainHand;
import org.bukkit.plugin.Plugin;

/**
 * HIM's visible body: a Paper {@link Mannequin} wearing the configured player skin.
 *
 * <p>The body is invisible by default and only shown to explicitly listed viewers, which is how
 * private hallucinations work without packets: other players' clients are never even told the
 * entity exists. It has no gravity and no AI; it is positioned by its owner every tick (directly, or
 * by following the hidden carrier during hunts). It is never saved to disk.
 */
public final class HimBody {

    private final Plugin plugin;
    private final Mannequin entity;
    private final Set<UUID> viewers = new HashSet<>();

    private HimBody(Plugin plugin, Mannequin entity) {
        this.plugin = plugin;
        this.entity = entity;
    }

    static HimBody spawn(Plugin plugin, EntityTags tags, SkinService skin, Location at) {
        World world = at.getWorld();
        Mannequin m = world.spawn(at, Mannequin.class, e -> {
            // Runs before the entity is added to the world: nobody ever sees a default state.
            e.setVisibleByDefault(false);
            e.setPersistent(false);
            e.setRemoveWhenFarAway(false);
            e.setProfile(skin.profile());
            SkinParts.Mutable parts = SkinParts.allParts();
            parts.setCapeEnabled(false);
            e.setSkinParts(parts);
            e.setMainHand(MainHand.RIGHT);
            e.setDescription(null);
            e.customName(null);
            e.setCustomNameVisible(false);
            e.setGravity(false);
            e.setImmovable(true);
            e.setSilent(true);
            e.setCollidable(false);
            e.setCanPickupItems(false);
            e.getEquipment().clear();
            tags.mark(e, EntityTags.Role.BODY);
        });
        return new HimBody(plugin, m);
    }

    public void show(Player player) {
        if (viewers.add(player.getUniqueId())) {
            player.showEntity(plugin, entity);
        }
    }

    public void hide(Player player) {
        if (viewers.remove(player.getUniqueId())) {
            player.hideEntity(plugin, entity);
        }
    }

    public void showAll(Collection<? extends Player> players) {
        for (Player p : players) {
            show(p);
        }
    }

    /** Hides the body from everyone without removing it (used while repositioning unseen). */
    public void hideAll() {
        for (UUID id : Set.copyOf(viewers)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                hide(p);
            } else {
                viewers.remove(id);
            }
        }
    }

    public Set<UUID> viewers() {
        return Set.copyOf(viewers);
    }

    public boolean isViewer(Player player) {
        return viewers.contains(player.getUniqueId());
    }

    /** Stops showing the body to a player who left the encounter (hides it if they are online). */
    public void forgetViewer(UUID id) {
        Player p = Bukkit.getPlayer(id);
        if (p != null) {
            hide(p);
        } else {
            viewers.remove(id);
        }
    }

    /** Moves to the location, keeping the location's rotation. Refuses to enter unloaded chunks. */
    public boolean moveTo(Location to) {
        if (!valid() || to.getWorld() != entity.getWorld()
                || !to.getWorld().isChunkLoaded(to.getBlockX() >> 4, to.getBlockZ() >> 4)) {
            return false;
        }
        boolean ok = entity.teleport(to);
        if (ok) {
            entity.setRotation(to.getYaw(), to.getPitch());
        }
        return ok;
    }

    /** Turns head and body towards a point. Body follows the head with a lag for a more uncanny look. */
    public void lookAt(Location target, boolean turnBody) {
        if (!valid()) {
            return;
        }
        Location eye = entity.getEyeLocation();
        float yaw = MathUtil.yawTowards(eye.getX(), eye.getZ(), target.getX(), target.getZ());
        float pitch = MathUtil.pitchTowards(eye.getX(), eye.getY(), eye.getZ(), target.getX(), target.getY(), target.getZ());
        float body = entity.getBodyYaw();
        entity.setRotation(yaw, pitch);
        if (turnBody) {
            entity.setBodyYaw(yaw);
        } else {
            // Keep the body mostly still: only follow once the head is turned unnaturally far.
            float delta = MathUtil.yawDelta(body, yaw);
            if (Math.abs(delta) > 50) {
                entity.setBodyYaw(body + delta - Math.signum(delta) * 50);
            } else {
                entity.setBodyYaw(body);
            }
        }
    }

    public Location location() {
        return entity.getLocation();
    }

    public Location eyeLocation() {
        return entity.getEyeLocation();
    }

    public UUID entityId() {
        return entity.getUniqueId();
    }

    public Mannequin entity() {
        return entity;
    }

    public boolean valid() {
        return entity.isValid();
    }

    void remove() {
        viewers.clear();
        if (!entity.isDead()) {
            entity.remove();
        }
    }
}
