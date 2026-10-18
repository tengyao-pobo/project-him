package dev.projecthim.him.sense;

import java.util.Optional;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;

/** Finds places a humanoid can stand without touching unloaded chunks. */
public final class Ground {

    private Ground() {
    }

    public static boolean loaded(World world, int x, int z) {
        return world.isChunkLoaded(x >> 4, z >> 4);
    }

    /** Solid enough to stand on and not something that would look absurd to stand on. */
    public static boolean standOn(Material m) {
        return m.isSolid() && !Tag.LEAVES.isTagged(m) && m != Material.CACTUS && m != Material.MAGMA_BLOCK
                && m != Material.POWDER_SNOW && !Tag.FENCES.isTagged(m) && !Tag.WALLS.isTagged(m)
                && !Tag.FENCE_GATES.isTagged(m) && m != Material.CAMPFIRE && m != Material.SOUL_CAMPFIRE;
    }

    /** Space a body can occupy: not solid, not liquid, not harmful. */
    public static boolean free(Material m) {
        if (m.isAir()) {
            return true;
        }
        return !m.isSolid() && m != Material.WATER && m != Material.LAVA && m != Material.FIRE
                && m != Material.SOUL_FIRE && m != Material.SWEET_BERRY_BUSH && m != Material.POWDER_SNOW
                && m != Material.COBWEB && m != Material.BUBBLE_COLUMN && m != Material.NETHER_PORTAL
                && m != Material.END_PORTAL && !m.name().endsWith("_DOOR") && !m.name().endsWith("TRAPDOOR");
    }

    public static boolean standable(World world, int x, int y, int z) {
        if (y <= world.getMinHeight() || y + 1 >= world.getMaxHeight()) {
            return false;
        }
        return standOn(world.getType(x, y - 1, z)) && free(world.getType(x, y, z)) && free(world.getType(x, y + 1, z));
    }

    /**
     * Searches a column around {@code yHint} for a standable position, preferring the closest one.
     * Works on the surface and in caves alike (it does not use the height map).
     */
    public static Optional<Location> findStandable(World world, int x, int yHint, int z, int up, int down) {
        if (!loaded(world, x, z)) {
            return Optional.empty();
        }
        for (int offset = 0; offset <= Math.max(up, down); offset++) {
            if (offset <= up && standable(world, x, yHint + offset, z)) {
                return Optional.of(new Location(world, x + 0.5, yHint + offset, z + 0.5));
            }
            if (offset > 0 && offset <= down && standable(world, x, yHint - offset, z)) {
                return Optional.of(new Location(world, x + 0.5, yHint - offset, z + 0.5));
            }
        }
        return Optional.empty();
    }

    /** Whether the block above the position up to the build limit lets sky light through. */
    public static boolean skyAbove(World world, int x, int y, int z) {
        return world.getHighestBlockYAt(x, z) < y;
    }

    public static int light(World world, int x, int y, int z) {
        return world.getBlockAt(x, y, z).getLightLevel();
    }
}
