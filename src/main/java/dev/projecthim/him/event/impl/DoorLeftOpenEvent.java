package dev.projecthim.him.event.impl;

import dev.projecthim.him.director.EncounterMode;
import dev.projecthim.him.director.EventSpec;
import dev.projecthim.him.director.Intent;
import dev.projecthim.him.director.Stage;
import dev.projecthim.him.event.EventContext;
import dev.projecthim.him.event.EventInstance;
import dev.projecthim.him.event.HorrorEvent;
import dev.projecthim.him.memory.HeatMap;
import dev.projecthim.him.memory.Outcome;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Door;

/**
 * While the player is away, a door of their home is opened. Nothing is taken, nothing is broken.
 * They will find it open when they come back. Requires door interaction to be enabled and HIM to
 * have a confident guess of where home is.
 */
final class DoorLeftOpenEvent implements HorrorEvent {

    private final EventSpec spec = EventSpec.builder("door_left_open", Intent.INTERFERE)
            .stage(Stage.WATCHED)
            .intensity(0.3)
            .cooldownMinutes(120)
            .weight(0.9)
            .modes(EncounterMode.SHARED)
            .requires(c -> c.baseKnown() && c.distanceToBase() > 40 && c.distanceToBase() < 200)
            .build();

    @Override
    public EventSpec spec() {
        return spec;
    }

    @Override
    public EventInstance create(EventContext ctx) {
        if (!ctx.him().config().worldEdits().doorInteraction()) {
            return null;
        }
        HeatMap.BaseEstimate base = ctx.session().baseEstimate();
        if (base == null || !base.world().equals(ctx.target().getWorld().getName())) {
            return null;
        }
        return new Instance(ctx, base);
    }

    private static final class Instance extends EventInstance {
        private final HeatMap.BaseEstimate base;

        Instance(EventContext ctx, HeatMap.BaseEstimate base) {
            super(ctx);
            this.base = base;
        }

        @Override
        protected boolean start() {
            World w = ctx.target().getWorld();
            int bx = (int) Math.floor(base.x());
            int by = (int) Math.floor(base.y());
            int bz = (int) Math.floor(base.z());
            for (int dx = -10; dx <= 10; dx++) {
                for (int dz = -10; dz <= 10; dz++) {
                    int x = bx + dx;
                    int z = bz + dz;
                    if (!w.isChunkLoaded(x >> 4, z >> 4)) {
                        continue;
                    }
                    for (int dy = -4; dy <= 4; dy++) {
                        Material m = w.getType(x, by + dy, z);
                        if (!Tag.WOODEN_DOORS.isTagged(m)) {
                            continue;
                        }
                        Block b = w.getBlockAt(x, by + dy, z);
                        if (b.getBlockData() instanceof Door door && door.getHalf() == Bisected.Half.BOTTOM && !door.isOpen()) {
                            door.setOpen(true);
                            b.setBlockData(door);
                            setWhere(b.getLocation());
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        @Override
        protected void tick(long tick) {
            finish(Outcome.UNNOTICED);
        }

        @Override
        protected void cleanup() {
            // Intentionally left open.
        }
    }
}
