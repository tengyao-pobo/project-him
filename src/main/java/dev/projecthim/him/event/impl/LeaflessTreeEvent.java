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
import dev.projecthim.him.world.ProtectionService;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.entity.Player;

/**
 * A tree in the distance loses all of its leaves: a real, visible change to the world. Only natural
 * trees (non-persistent leaves, which players cannot place) in wild areas qualify, every block is
 * journaled, and the leaves grow back later while nobody is near.
 */
final class LeaflessTreeEvent implements HorrorEvent {

    private static final int MAX_LEAVES = 260;

    private final EventSpec spec = EventSpec.builder("leafless_tree", Intent.INTERFERE)
            .stage(Stage.NOTICED)
            .intensity(0.3)
            .cooldownMinutes(90)
            .weight(0.8)
            .modes(EncounterMode.SHARED)
            .modifiesWorld()
            .requires(c -> c.dimension() == PlayerContext.Dimension.OVERWORLD && c.skyVisible())
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
            for (int attempt = 0; attempt < 24; attempt++) {
                double angle = ctx.him().random().nextDouble() * Math.PI * 2;
                double dist = 30 + ctx.him().random().nextDouble() * 40;
                int x = (int) Math.floor(p.getX() + Math.cos(angle) * dist);
                int z = (int) Math.floor(p.getZ() + Math.sin(angle) * dist);
                if (!w.isChunkLoaded(x >> 4, z >> 4)) {
                    continue;
                }
                Block top = w.getHighestBlockAt(x, z);
                if (!Tag.LEAVES.isTagged(top.getType())) {
                    continue;
                }
                List<Block> leaves = naturalCanopy(top);
                if (leaves.size() < 12 || !ctx.him().protection().areaIsWild(top.getLocation())) {
                    continue;
                }
                long revertAt = ctx.him().clock().now()
                        + (long) (ctx.him().config().worldEdits().revertAfterMinutes() * 60_000L);
                int changed = 0;
                for (Block leaf : leaves) {
                    if (ctx.him().journal().apply(leaf, Material.AIR.createBlockData(), revertAt, "leafless_tree")) {
                        changed++;
                    }
                }
                if (changed > 0) {
                    setWhere(top.getLocation());
                    int n = changed;
                    Location at = top.getLocation();
                    ctx.him().log().debug(() -> "stripped " + n + " leaves at " + at.toVector());
                    return true;
                }
            }
            return false;
        }

        /** Connected natural leaves around a trunk, bounded in size and radius. */
        private List<Block> naturalCanopy(Block start) {
            List<Block> out = new ArrayList<>();
            Set<Block> seen = new HashSet<>();
            ArrayDeque<Block> queue = new ArrayDeque<>();
            queue.add(start);
            seen.add(start);
            BlockFace[] faces = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.UP, BlockFace.DOWN};
            boolean trunk = false;
            while (!queue.isEmpty() && out.size() < MAX_LEAVES) {
                Block b = queue.poll();
                if (Tag.LOGS.isTagged(b.getType())) {
                    trunk = true;
                    continue;
                }
                if (!(b.getBlockData() instanceof Leaves leaf) || leaf.isPersistent()
                        || !ProtectionService.isNatural(b.getType())) {
                    continue;
                }
                if (ctx.him().journal().contains(b)) {
                    continue;
                }
                out.add(b);
                for (BlockFace f : faces) {
                    Block n = b.getRelative(f);
                    if (Math.abs(n.getX() - start.getX()) > 5 || Math.abs(n.getZ() - start.getZ()) > 5
                            || !b.getWorld().isChunkLoaded(n.getX() >> 4, n.getZ() >> 4)) {
                        continue;
                    }
                    if (seen.add(n) && (Tag.LEAVES.isTagged(n.getType()) || Tag.LOGS.isTagged(n.getType()))) {
                        queue.add(n);
                    }
                }
            }
            return trunk ? out : List.of();
        }

        @Override
        protected void tick(long tick) {
            finish(Outcome.UNNOTICED);
        }

        @Override
        protected void cleanup() {
            // Restoration is the journal's job, at a time when nobody is watching.
        }
    }
}
