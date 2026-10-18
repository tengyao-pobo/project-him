package dev.projecthim.him.event.impl;

import dev.projecthim.him.director.EncounterMode;
import dev.projecthim.him.director.EventSpec;
import dev.projecthim.him.director.Intent;
import dev.projecthim.him.director.Stage;
import dev.projecthim.him.event.EventContext;
import dev.projecthim.him.event.EventInstance;
import dev.projecthim.him.event.HorrorEvent;
import dev.projecthim.him.memory.Outcome;
import dev.projecthim.him.platform.SoundCues;
import dev.projecthim.him.sense.Sight;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;

/**
 * A block in the wall in front of the player starts to crack, as if someone were mining it from the
 * other side. It gets almost through, then stops. Nothing comes out. The cracks fade a moment later.
 * Subverted: after the silence, a single knock from right beside the player.
 */
final class WallCrackEvent implements HorrorEvent {

    private static final BlockFace[] SIDES = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST,
            BlockFace.UP, BlockFace.DOWN};

    private final EventSpec spec = EventSpec.builder("wall_crack", Intent.INTERFERE)
            .stage(Stage.NOTICED)
            .intensity(0.4)
            .cooldownMinutes(30)
            .weight(1.1)
            .modes(EncounterMode.PRIVATE)
            .requires(c -> c.underground() || c.sheltered())
            .build();

    @Override
    public EventSpec spec() {
        return spec;
    }

    @Override
    public EventInstance create(EventContext ctx) {
        return ctx.him().fakes().enabled() ? new Instance(ctx) : null;
    }

    private static final class Instance extends EventInstance {
        private Block block;
        private int stage;
        private long nextAt;
        private long holdUntil = -1;
        private boolean knocked;
        private int looked;
        private final int sourceId;

        Instance(EventContext ctx) {
            super(ctx);
            // A random id that belongs to no entity, so the client tracks this crack on its own.
            sourceId = 0x5F000000 | ctx.him().random().nextInt(0xFFFFFF);
        }

        @Override
        protected boolean start() {
            Player p = ctx.target();
            Block looking = p.getTargetBlockExact(9);
            if (looking == null || !looking.getType().isOccluding() || looking.getLocation().distance(p.getLocation()) < 3) {
                return false;
            }
            // A neighbour of the looked-at block with an exposed face, so it is not exactly in the crosshair.
            for (int i = 0; i < 6; i++) {
                Block candidate = looking.getRelative(SIDES[ctx.him().random().nextInt(SIDES.length)]);
                if (candidate.getType().isOccluding() && exposed(candidate)) {
                    block = candidate;
                    break;
                }
            }
            if (block == null) {
                block = looking;
            }
            setWhere(block.getLocation());
            nextAt = ctx.him().tick() + 10;
            return true;
        }

        private static boolean exposed(Block b) {
            for (BlockFace f : SIDES) {
                if (!b.getRelative(f).getType().isOccluding()) {
                    return true;
                }
            }
            return false;
        }

        @Override
        protected void tick(long tick) {
            Player p = ctx.target();
            Location center = block.getLocation().add(0.5, 0.5, 0.5);
            if (Sight.viewAngle(p, center) < 20 && ++looked >= 6) {
                setOutcome(p.getUniqueId(), Outcome.NOTICED);
            }
            if (tick < nextAt) {
                return;
            }
            if (holdUntil < 0) {
                stage++;
                ctx.him().fakes().crack(p, block.getLocation(), stage / 10f, sourceId, 20 * 30, tick, ctx.instanceId());
                SoundCues.dig(p, block, 0.8f);
                if (stage >= 9) {
                    holdUntil = tick + 50;
                }
                nextAt = tick + 6 + ctx.him().random().nextInt(4);
                return;
            }
            if (tick >= holdUntil) {
                if (ctx.subvert() && !knocked) {
                    knocked = true;
                    double[] dir = dev.projecthim.him.util.MathUtil.horizontalDirection(p.getLocation().getYaw());
                    // Perpendicular to the view, on the player's side.
                    Location beside = p.getLocation().add(-dir[1] * 1.5, 1, dir[0] * 1.5);
                    SoundCues.play(p, beside, block.getBlockData().getSoundGroup().getHitSound(),
                            org.bukkit.SoundCategory.BLOCKS, 1.0f, 0.6f);
                    nextAt = tick + 30;
                    return;
                }
                finish(Outcome.UNNOTICED);
            }
        }

        @Override
        protected void cleanup() {
            ctx.him().fakes().revertOwner(ctx.instanceId());
        }

        @Override
        protected int maxTicks() {
            return 20 * 30;
        }
    }
}
