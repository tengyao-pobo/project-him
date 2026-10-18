package dev.projecthim.him.event.impl;

import dev.projecthim.him.director.EncounterMode;
import dev.projecthim.him.director.EventSpec;
import dev.projecthim.him.director.Intent;
import dev.projecthim.him.director.Stage;
import dev.projecthim.him.director.Trigger;
import dev.projecthim.him.event.EventContext;
import dev.projecthim.him.event.EventInstance;
import dev.projecthim.him.event.HorrorEvent;
import dev.projecthim.him.memory.Outcome;
import dev.projecthim.him.memory.Personality.Trait;
import dev.projecthim.him.platform.SoundCues;
import dev.projecthim.him.sense.SpotFinder;
import java.util.Optional;
import org.bukkit.Location;
import org.bukkit.SoundCategory;
import org.bukkit.block.data.BlockData;

/**
 * HIM copies the player. A moment after the player breaks a block, the very same block breaks
 * somewhere out of sight. Sometimes twice. Chosen more often the more HIM likes mimicry.
 */
final class MimicEchoEvent implements HorrorEvent {

    private final EventSpec spec = EventSpec.builder("mimic_echo", Intent.PRESENCE)
            .stage(Stage.NOTICED)
            .intensity(0.2)
            .cooldownMinutes(15)
            .weight(1.5)
            .modes(EncounterMode.PRIVATE)
            .triggers(Trigger.MINE)
            .requires(c -> c.underground() || c.dark())
            .build();

    @Override
    public EventSpec spec() {
        return spec;
    }

    @Override
    public EventInstance create(EventContext ctx) {
        BlockData broken = ctx.session().lastBroken();
        if (broken == null) {
            return null;
        }
        // Mimicry trait as a gate: an entity that does not like copying rarely does this.
        double mimicry = ctx.him().memory().world().personality().get(Trait.MIMICRY);
        if (ctx.him().random().nextDouble() > 0.3 + mimicry) {
            return null;
        }
        return new Instance(ctx, broken);
    }

    private static final class Instance extends AudioInstance {
        private final BlockData broken;
        private Location source;
        private long firstAt;
        private long secondAt = -1;

        Instance(EventContext ctx, BlockData broken) {
            super(ctx);
            this.broken = broken;
        }

        @Override
        protected boolean start() {
            Optional<SpotFinder.Spot> spot = ctx.him().spots().hiddenOrBehind(ctx.target(), 8, 15);
            if (spot.isEmpty()) {
                return false;
            }
            source = spot.get().feet().clone().add(0, 1, 0);
            setWhere(source);
            firstAt = ctx.him().tick() + 25 + ctx.him().random().nextInt(35);
            if (ctx.subvert() || ctx.him().random().nextDouble() < 0.35) {
                secondAt = firstAt + 25 + ctx.him().random().nextInt(20);
            }
            return true;
        }

        @Override
        protected void tick(long tick) {
            observe(ctx.target(), source);
            if (tick == firstAt || tick == secondAt) {
                SoundCues.play(ctx.target(), source, broken.getSoundGroup().getBreakSound(), SoundCategory.BLOCKS, 0.85f, 0.95f);
            }
            long end = (secondAt > 0 ? secondAt : firstAt) + 40;
            if (tick >= end) {
                finish(Outcome.UNNOTICED);
            }
        }

        @Override
        protected int maxTicks() {
            return 20 * 15;
        }
    }
}
