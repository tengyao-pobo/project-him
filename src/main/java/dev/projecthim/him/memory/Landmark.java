package dev.projecthim.him.memory;

import java.util.UUID;

/**
 * A place that matters to HIM: where a hunt ended, where someone died, where he was attacked.
 * Memory-linked events revisit these places when the player returns.
 */
public record Landmark(Kind kind, UUID player, String world, int x, int y, int z, long time) {

    public enum Kind {
        ESCAPE_SITE, DEATH_SITE, ATTACK_SITE, LOGOUT_SITE, FIRST_SIGHTING
    }

    public double distanceSquared(String otherWorld, double ox, double oy, double oz) {
        if (!world.equals(otherWorld)) {
            return Double.MAX_VALUE;
        }
        double dx = x - ox;
        double dy = y - oy;
        double dz = z - oz;
        return dx * dx + dy * dy + dz * dz;
    }
}
