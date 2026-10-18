package dev.projecthim.him.event.impl;

import dev.projecthim.him.director.EncounterMode;
import dev.projecthim.him.director.EventSpec;
import dev.projecthim.him.director.Intent;
import dev.projecthim.him.director.PlayerContext;
import dev.projecthim.him.director.Stage;
import dev.projecthim.him.event.EventContext;
import dev.projecthim.him.event.EventInstance;
import dev.projecthim.him.event.HorrorEvent;
import dev.projecthim.him.memory.Outcome;
import dev.projecthim.him.sense.Ground;
import dev.projecthim.him.sense.Sight;
import dev.projecthim.him.world.ProtectionService;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;

/**
 * A perfectly straight 1x2 tunnel leading into the rock from a cave wall the player is not looking
 * at. Nobody dug it. Only natural stone is carved, never next to water or lava, every block is
 * journaled and the tunnel closes up again later while nobody is near.
 */
final class TunnelEvent implements HorrorEvent {

    private static final BlockFace[] HORIZONTAL = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};
    private static final BlockFace[] ALL = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST,
            BlockFace.UP, BlockFace.DOWN};

    private final EventSpec spec = EventSpec.builder("tunnel", Intent.INTERFERE)
            .stage(Stage.WATCHED)
            .intensity(0.35)
            .cooldownMinutes(120)
            .weight(0.8)
            .modes(EncounterMode.SHARED)
            .modifiesWorld()
            .requires(PlayerContext::underground)
            .build();

    @Override
    public EventSpec spec() {
        return spec;
    }

    @Override
    public EventInstance create(EventContext ctx) {
        return new Instance(ctx);
    }

    private static final class Instance extends EventInstance {
        Instance(EventContext ctx) {
            super(ctx);
        }

        @Override
        protected boolean start() {
            Player p = ctx.target();
            World w = p.getWorld();
            Location base = p.getLocation();
            for (int attempt = 0; attempt < 16; attempt++) {
                BlockFace dir = HORIZONTAL[ctx.him().random().nextInt(4)];
                int startDist = 4 + ctx.him().random().nextInt(8);
                int sx = base.getBlockX() + dir.getModX() * startDist + ctx.him().random().nextInt(5) - 2;
                int sz = base.getBlockZ() + dir.getModZ() * startDist + ctx.him().random().nextInt(5) - 2;
                // Walk along the direction until the cave floor meets a wall.
                Location floor = Ground.findStandable(w, sx, base.getBlockY(), sz, 3, 3).orElse(null);
                if (floor == null) {
                    continue;
                }
                Block feet = floor.getBlock();
                Block wall = feet.getRelative(dir);
                if (!wall.getType().isOccluding() || !wall.getRelative(BlockFace.UP).getType().isOccluding()) {
                    continue;
                }
                Location mouth = wall.getLocation().add(0.5, 1, 0.5);
                if (Sight.viewAngle(p, mouth) < 70 || !ctx.him().protection().areaIsWild(mouth)) {
                    continue;
                }
                List<Block> carve = plan(wall, dir, 8 + ctx.him().random().nextInt(7));
                if (carve.size() < 10) {
                    continue;
                }
                long revertAt = ctx.him().clock().now()
                        + (long) (ctx.him().config().worldEdits().revertAfterMinutes() * 60_000L);
                for (Block b : carve) {
                    ctx.him().journal().apply(b, Material.CAVE_AIR.createBlockData(), revertAt, "tunnel");
                }
                setWhere(mouth);
                return true;
            }
            return false;
        }

        /** Blocks for a 1 wide, 2 high straight tunnel; stops at anything that is not plain natural rock. */
        private List<Block> plan(Block wall, BlockFace dir, int length) {
            List<Block> out = new ArrayList<>();
            Block lower = wall;
            for (int i = 0; i < length; i++) {
                Block upper = lower.getRelative(BlockFace.UP);
                if (!carvable(lower) || !carvable(upper)) {
                    break;
                }
                out.add(lower);
                out.add(upper);
                lower = lower.getRelative(dir);
            }
            return out;
        }

        private boolean carvable(Block b) {
            if (!ProtectionService.isNatural(b.getType()) || !b.getType().isOccluding() || ctx.him().journal().contains(b)
                    || !b.getWorld().isChunkLoaded(b.getX() >> 4, b.getZ() >> 4)) {
                return false;
            }
            for (BlockFace f : ALL) {
                Material n = b.getRelative(f).getType();
                if (n == Material.WATER || n == Material.LAVA || n == Material.GRAVEL || n == Material.SAND) {
                    return false;
                }
            }
            return true;
        }

        @Override
        protected void tick(long tick) {
            finish(Outcome.UNNOTICED);
        }

        @Override
        protected void cleanup() {
            // Restoration is the journal's job.
        }
    }
}
