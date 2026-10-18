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
        BODY, CARRIER
    }

    private final NamespacedKey roleKey;

    public EntityTags(Plugin plugin) {
        this.roleKey = new NamespacedKey(plugin, "role");
    }

    public void mark(Entity entity, Role role) {
        entity.getPersistentDataContainer().set(roleKey, PersistentDataType.STRING, role.name());
    }

    public boolean isOurs(Entity entity) {
        return entity.getPersistentDataContainer().has(roleKey, PersistentDataType.STRING);
    }
}
