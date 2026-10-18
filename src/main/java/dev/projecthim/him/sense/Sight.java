package dev.projecthim.him.sense;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

/**
 * Line of sight as a player would experience it. Unlike a collision ray trace, glass and panes are
 * see-through, leaves and partial blocks only partially block the view, and the ray never loads
 * chunks (an unloaded chunk counts as blocked).
 */
public final class Sight {

    /** Rough half angle of what a player perceives on screen without turning (default FOV, 16:9). */
    public static final double PERIPHERAL_HALF_ANGLE = 62;
    /** Half angle that counts as "looking at" something. */
    public static final double FOCUS_HALF_ANGLE = 11;

    private Sight() {
    }

    /**
     * Visibility between two points from 0 (blocked) to 1 (clear). Uses a voxel walk
     * (Amanatides and Woo) and accumulates per block opacity.
     */
    public static double visibility(World world, Vector from, Vector to) {
        Vector d = to.clone().subtract(from);
        double length = d.length();
        if (length < 1.0E-6) {
            return 1.0;
        }
        if (length > 192) {
            return 0.0;
        }
        d.multiply(1.0 / length);
        int x = floor(from.getX());
        int y = floor(from.getY());
        int z = floor(from.getZ());
        int endX = floor(to.getX());
        int endY = floor(to.getY());
        int endZ = floor(to.getZ());
        int stepX = d.getX() > 0 ? 1 : -1;
        int stepY = d.getY() > 0 ? 1 : -1;
        int stepZ = d.getZ() > 0 ? 1 : -1;
        double tDeltaX = d.getX() == 0 ? Double.MAX_VALUE : Math.abs(1.0 / d.getX());
        double tDeltaY = d.getY() == 0 ? Double.MAX_VALUE : Math.abs(1.0 / d.getY());
        double tDeltaZ = d.getZ() == 0 ? Double.MAX_VALUE : Math.abs(1.0 / d.getZ());
        double tMaxX = d.getX() == 0 ? Double.MAX_VALUE : boundary(from.getX(), x, stepX) * tDeltaX;
        double tMaxY = d.getY() == 0 ? Double.MAX_VALUE : boundary(from.getY(), y, stepY) * tDeltaY;
        double tMaxZ = d.getZ() == 0 ? Double.MAX_VALUE : boundary(from.getZ(), z, stepZ) * tDeltaZ;

        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight();
        double opacity = 0;
        int lastCx = Integer.MIN_VALUE;
        int lastCz = Integer.MIN_VALUE;
        int guard = 0;
        while (guard++ < 600) {
            // Step first: the starting voxel (the viewer's head) never blocks.
            if (tMaxX < tMaxY && tMaxX < tMaxZ) {
                if (tMaxX > length) {
                    break;
                }
                x += stepX;
                tMaxX += tDeltaX;
            } else if (tMaxY < tMaxZ) {
                if (tMaxY > length) {
                    break;
                }
                y += stepY;
                tMaxY += tDeltaY;
            } else {
                if (tMaxZ > length) {
                    break;
                }
                z += stepZ;
                tMaxZ += tDeltaZ;
            }
            if (x == endX && y == endY && z == endZ) {
                // The target's own voxel (HIM's head) does not count.
                break;
            }
            if (y < minY || y >= maxY) {
                continue;
            }
            int cx = x >> 4;
            int cz = z >> 4;
            if (cx != lastCx || cz != lastCz) {
                if (!world.isChunkLoaded(cx, cz)) {
                    return 0.0;
                }
                lastCx = cx;
                lastCz = cz;
            }
            opacity += opacity(world.getType(x, y, z));
            if (opacity >= 1.0) {
                return 0.0;
            }
        }
        return 1.0 - opacity;
    }

    private static double boundary(double pos, int cell, int step) {
        return step > 0 ? (cell + 1 - pos) : (pos - cell);
    }

    private static int floor(double v) {
        return (int) Math.floor(v);
    }

    static double opacity(Material m) {
        if (m.isAir()) {
            return 0;
        }
        if (Tag.LEAVES.isTagged(m)) {
            return 0.34;
        }
        if (m.isOccluding()) {
            return 1.0;
        }
        String name = m.name();
        if (name.contains("GLASS") || name.endsWith("_BARS") || m == Material.WATER) {
            return name.contains("TINTED") ? 1.0 : 0.02;
        }
        if (m == Material.LAVA) {
            return 1.0;
        }
        if (m.isSolid()) {
            // Slabs, stairs, walls, fences, chests and the like.
            return 0.4;
        }
        return 0.0;
    }

    /** Angle in degrees between the player's view direction and the direction to the point. */
    public static double viewAngle(Player player, Location point) {
        Location eye = player.getEyeLocation();
        Vector to = point.toVector().subtract(eye.toVector());
        if (to.lengthSquared() < 1.0E-6) {
            return 0;
        }
        return Math.toDegrees(eye.getDirection().angle(to));
    }

    /**
     * Whether the player can currently see a humanoid standing at {@code feet}: it must be within
     * the field of view and at least partially unobstructed at the head or body.
     */
    public static boolean canSee(Player player, Location feet, double halfAngle) {
        if (player.getWorld() != feet.getWorld()) {
            return false;
        }
        Location head = feet.clone().add(0, 1.62, 0);
        if (viewAngle(player, head) > halfAngle && viewAngle(player, feet.clone().add(0, 0.9, 0)) > halfAngle) {
            return false;
        }
        return exposure(player.getEyeLocation(), feet) >= 0.35;
    }

    /** Best visibility of a humanoid's head or torso from an eye position. */
    public static double exposure(Location eye, Location feet) {
        World w = feet.getWorld();
        double head = visibility(w, eye.toVector(), feet.toVector().add(new Vector(0, 1.62, 0)));
        if (head >= 0.9) {
            return head;
        }
        double body = visibility(w, eye.toVector(), feet.toVector().add(new Vector(0, 1.0, 0)));
        return Math.max(head, body);
    }

    public static boolean isLookingAt(Player player, Location feet) {
        return canSee(player, feet, FOCUS_HALF_ANGLE);
    }
}
