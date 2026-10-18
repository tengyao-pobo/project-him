package dev.projecthim.him.platform;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * Marks every entity HIM creates. Entities are also non-persistent, so they are never written to
 * disk; the tag is a second line of defence that lets the plugin find and remove strays (for example
 * after a crash or a hot reload by another plugin).
 */
public final class EntityTags {

    public enum Role {
        BODY, CARRIER,
        /** An arrow or potion he threw; only its target may be affected by it. */
        PROJECTILE
    }

    private final NamespacedKey roleKey;
    private final NamespacedKey targetKey;
    private final NamespacedKey minionKey;

    public EntityTags(Plugin plugin) {
        this.roleKey = new NamespacedKey(plugin, "role");
        this.targetKey = new NamespacedKey(plugin, "target");
        this.minionKey = new NamespacedKey(plugin, "minion");
    }

    /** A monster he summoned: real and killable, but it only fights {@code target} and is removed after. */
    public void markMinion(Entity entity, java.util.UUID target) {
        entity.getPersistentDataContainer().set(minionKey, PersistentDataType.STRING, target.toString());
    }

    public java.util.UUID minionTarget(Entity entity) {
        String s = entity.getPersistentDataContainer().get(minionKey, PersistentDataType.STRING);
        if (s == null) {
            return null;
        }
        try {
            return java.util.UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public void markProjectile(Entity entity, java.util.UUID target) {
        mark(entity, Role.PROJECTILE);
        entity.getPersistentDataContainer().set(targetKey, PersistentDataType.STRING, target.toString());
    }

    /** The player a projectile of his is meant for, or null if the entity is not one of his projectiles. */
    public java.util.UUID projectileTarget(Entity entity) {
        String s = entity.getPersistentDataContainer().get(targetKey, PersistentDataType.STRING);
        if (s == null) {
            return null;
        }
        try {
            return java.util.UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public void mark(Entity entity, Role role) {
        entity.getPersistentDataContainer().set(roleKey, PersistentDataType.STRING, role.name());
    }

    public boolean isOurs(Entity entity) {
        return entity.getPersistentDataContainer().has(roleKey, PersistentDataType.STRING);
    }
}
