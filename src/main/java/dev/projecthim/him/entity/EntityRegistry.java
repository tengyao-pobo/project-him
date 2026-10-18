package dev.projecthim.him.entity;

import dev.projecthim.him.platform.EntityTags;
import dev.projecthim.him.platform.SkinService;
import dev.projecthim.him.util.HimLog;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

/**
 * Single owner of every entity HIM creates. Spawning goes through here so that nothing can leak:
 * entities are tracked, removed on shutdown, and any tagged entity that is not tracked (a stray from
 * a crash or a foreign reload) is removed as soon as it is seen.
 */
public final class EntityRegistry {

    private final Plugin plugin;
    private final EntityTags tags;
    private final HimLog log;
    private SkinService skin;
    private final Map<UUID, HimBody> bodies = new HashMap<>();
    private final java.util.Set<HimCarrier> carriers = new java.util.HashSet<>();

    public EntityRegistry(Plugin plugin, EntityTags tags, SkinService skin, HimLog log) {
        this.plugin = plugin;
        this.tags = tags;
        this.skin = skin;
        this.log = log;
    }

    public void setSkin(SkinService skin) {
        this.skin = skin;
    }

    /** HIM's own configured skin. */
    public ResolvableProfile skinProfile() {
        return skin.profile();
    }

    /**
     * Spawns a body. Returns null if the spawn was refused (another plugin cancelled it, or the
     * chunk is not loaded) so callers can simply skip the encounter.
     */
    public HimBody spawnBody(Location at) {
        return spawnBody(at, skin.profile());
    }

    /** Spawns a body wearing the given profile instead of HIM's own skin. */
    public HimBody spawnBody(Location at, ResolvableProfile profile) {
        if (!at.getWorld().isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4)) {
            return null;
        }
        HimBody body = HimBody.spawn(plugin, tags, profile, at);
        if (!body.valid()) {
            log.debug(() -> "body spawn was cancelled at " + at.toVector());
            body.remove();
            return null;
        }
        bodies.put(body.entityId(), body);
        return body;
    }

    /** Creates a virtual navigation carrier, or returns null if the location is not loaded. */
    public HimCarrier spawnCarrier(Location at) {
        if (!at.getWorld().isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4)) {
            return null;
        }
        HimCarrier carrier = new HimCarrier(at);
        carriers.add(carrier);
        return carrier;
    }

    public void remove(HimBody body) {
        if (body != null) {
            bodies.remove(body.entityId());
            body.remove();
        }
    }

    public void remove(HimCarrier carrier) {
        if (carrier != null) {
            carriers.remove(carrier);
            carrier.remove();
        }
    }

    public boolean isTracked(Entity entity) {
        return bodies.containsKey(entity.getUniqueId());
    }

    public HimBody bodyOf(Entity entity) {
        return bodies.get(entity.getUniqueId());
    }

    public boolean isOurs(Entity entity) {
        return tags.isOurs(entity);
    }

    public int activeBodies() {
        return bodies.size();
    }

    public int activeCarriers() {
        return carriers.size();
    }

    /** Removes tagged entities that are not tracked. Called for freshly loaded entity lists. */
    public int sweep(Collection<Entity> entities) {
        int removed = 0;
        for (Entity e : entities) {
            if (tags.isOurs(e) && !isTracked(e)) {
                e.remove();
                removed++;
            }
        }
        if (removed > 0) {
            log.debug(() -> "removed stray HIM entities");
        }
        return removed;
    }

    public int sweepAllWorlds() {
        int removed = 0;
        for (World w : Bukkit.getWorlds()) {
            removed += sweep(List.copyOf(w.getEntities()));
        }
        return removed;
    }

    /** Drops wrappers whose entity vanished on its own (chunk unload, /kill). */
    public void pruneInvalid() {
        bodies.values().removeIf(b -> !b.valid());
        carriers.removeIf(c -> !c.valid());
    }

    public void removeAll() {
        for (HimBody b : List.copyOf(bodies.values())) {
            remove(b);
        }
        for (HimCarrier c : List.copyOf(carriers)) {
            remove(c);
        }
    }
}
