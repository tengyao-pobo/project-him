package dev.projecthim.him.world;

import dev.projecthim.him.config.HimConfig;
import dev.projecthim.him.memory.HeatMap;
import dev.projecthim.him.memory.MemoryService;
import dev.projecthim.him.memory.PlayerMemory;
import dev.projecthim.him.memory.WorldState;
import java.util.EnumSet;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

/**
 * Decides whether HIM may really change a block. Real changes are restricted to natural terrain far
 * away from anything players built or call home; everything else must use fake blocks.
 */
public final class ProtectionService {

    private static final Set<Material> NATURAL = EnumSet.of(
            Material.STONE, Material.DEEPSLATE, Material.ANDESITE, Material.DIORITE, Material.GRANITE,
            Material.TUFF, Material.DIRT, Material.GRASS_BLOCK, Material.GRAVEL, Material.SAND, Material.SANDSTONE,
            Material.COBBLED_DEEPSLATE, Material.CALCITE, Material.COARSE_DIRT, Material.PODZOL, Material.NETHERRACK);

    private final MemoryService memory;
    private HimConfig.WorldEdits settings;

    public ProtectionService(MemoryService memory, HimConfig.WorldEdits settings) {
        this.memory = memory;
        this.settings = settings;
    }

    public void configure(HimConfig.WorldEdits settings) {
        this.settings = settings;
    }

    public HimConfig.WorldEdits settings() {
        return settings;
    }

    /** Whether real changes are allowed at all in this world right now. */
    public boolean realChangesAllowed(World world) {
        if (!settings.realChanges()) {
            return false;
        }
        if (settings.respectMobGriefing()) {
            Boolean griefing = world.getGameRuleValue(GameRules.MOB_GRIEFING);
            return griefing == null || griefing;
        }
        return true;
    }

    public static boolean isNatural(Material m) {
        return NATURAL.contains(m) || Tag.LEAVES.isTagged(m) || Tag.LOGS.isTagged(m) && !m.name().startsWith("STRIPPED");
    }

    /** Whether the area around a location is far from bases, spawn and built-up cells. */
    public boolean areaIsWild(Location at) {
        World world = at.getWorld();
        if (!realChangesAllowed(world)) {
            return false;
        }
        double r = settings.protectRadius();
        double r2 = r * r;
        Location spawn = world.getSpawnLocation();
        if (spawn.getWorld() == world && spawn.distanceSquared(at) < r2) {
            return false;
        }
        WorldState state = memory.world();
        int threshold = settings.builtCellThreshold();
        int cellRadius = (int) Math.ceil(r / 16.0);
        String worldName = world.getName();
        for (int dx = -cellRadius; dx <= cellRadius; dx++) {
            for (int dz = -cellRadius; dz <= cellRadius; dz++) {
                if (state.buildDensity(worldName, at.getBlockX() + dx * 16, at.getBlockZ() + dz * 16) >= threshold) {
                    return false;
                }
            }
        }
        long now = System.currentTimeMillis();
        for (Player p : world.getPlayers()) {
            Location respawn = p.getRespawnLocation(false);
            if (respawn != null && respawn.getWorld() == world && respawn.distanceSquared(at) < r2) {
                return false;
            }
            PlayerMemory m = memory.get(p.getUniqueId());
            if (m != null) {
                HeatMap.BaseEstimate base = m.heat().estimateBase(worldName, now, 10L * 86_400_000L).orElse(null);
                if (base != null && sq(base.x() - at.getX()) + sq(base.z() - at.getZ()) < r2) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Whether this specific block may be changed for real. */
    public boolean mayChange(Block block) {
        return isNatural(block.getType()) && Bukkit.isPrimaryThread();
    }

    private static double sq(double v) {
        return v * v;
    }
}
