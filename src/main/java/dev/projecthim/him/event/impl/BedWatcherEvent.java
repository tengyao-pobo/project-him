package dev.projecthim.him.event.impl;

import dev.projecthim.him.director.EncounterMode;
import dev.projecthim.him.director.EventSpec;
import dev.projecthim.him.director.Intent;
import dev.projecthim.him.director.Stage;
import dev.projecthim.him.director.Trigger;
import dev.projecthim.him.event.Apparition;
import dev.projecthim.him.event.EventContext;
import dev.projecthim.him.event.EventInstance;
import dev.projecthim.him.event.HorrorEvent;
import dev.projecthim.him.sense.SpotFinder;
import java.util.List;
import java.util.Optional;

/**
 * You wake up, and for a moment someone is standing in the room. Triggered only by leaving a bed.
 */
final class BedWatcherEvent implements HorrorEvent {

    private final EventSpec spec = EventSpec.builder("bed_watcher", Intent.OBSERVE)
            .stage(Stage.WATCHED)
            .intensity(0.6)
            .cooldownMinutes(90)
            .weight(1.0)
            .modes(EncounterMode.PRIVATE)
            .triggers(Trigger.WAKE)
            .visual()
            .build();

    @Override
    public EventSpec spec() {
        return spec;
    }

    @Override
    public EventInstance create(EventContext ctx) {
        return new Instance(ctx);
    }

    private static final class Instance extends VisualInstance {
        private Apparition him;
        private final int delay;
        private long shownAt = -1;

        Instance(EventContext ctx) {
            super(ctx);
            delay = 8 + ctx.him().random().nextInt(20);
        }

        @Override
        protected boolean start() {
            return true;
        }

        @Override
        protected void tick(long tick) {
            if (him == null) {
                if (age(tick) < delay) {
                    return;
                }
                Optional<SpotFinder.Spot> spot = ctx.him().spots().distant(target(), 3, 7, false);
                if (spot.isEmpty()) {
                    finishWithReactions();
                    return;
                }
                him = spawn(spot.get().feet(), List.of(target())).orElse(null);
                if (him == null) {
                    finishWithReactions();
                    return;
                }
                shownAt = tick;
            }
            if (tickApparitions(tick)) {
                finishWithReactions();
                return;
            }
            if (him.anyLookingFor(ctx.subvert() ? 30 : 8) || tick - shownAt > 50 || him.nearestDistance() < 2.2) {
                vanish(him);
            }
        }

        @Override
        protected int maxTicks() {
            return 20 * 15;
        }
    }
}
