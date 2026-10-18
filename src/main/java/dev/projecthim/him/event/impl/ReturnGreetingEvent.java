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
import dev.projecthim.him.memory.Landmark;
import dev.projecthim.him.sense.SpotFinder;
import java.util.List;
import java.util.Optional;

/**
 * Shortly after the player logs in, he is already there, watching. Much likelier (and closer) if the
 * player left in the middle of an encounter last time: he waited.
 */
final class ReturnGreetingEvent implements HorrorEvent {

    private final EventSpec spec = EventSpec.builder("return_greeting", Intent.OBSERVE)
            .stage(Stage.NOTICED)
            .intensity(0.45)
            .cooldownMinutes(120)
            .weight(1.0)
            .modes(EncounterMode.PRIVATE)
            .triggers(Trigger.JOIN)
            .visual()
            .requires(c -> !c.inWater())
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

        Instance(EventContext ctx) {
            super(ctx);
        }

        @Override
        protected boolean start() {
            Landmark pending = ctx.session().memory().pendingReturn();
            double min = pending != null ? 9 : 16;
            double max = pending != null ? 18 : 32;
            Optional<SpotFinder.Spot> spot = ctx.him().spots().distant(target(), min, max, false);
            if (spot.isEmpty()) {
                return false;
            }
            him = spawn(spot.get().feet(), List.of(target())).orElse(null);
            if (him == null) {
                return false;
            }
            if (pending != null) {
                ctx.session().memory().setPendingReturn(null);
            }
            return true;
        }

        @Override
        protected void tick(long tick) {
            if (tickApparitions(tick)) {
                finishWithReactions();
                return;
            }
            if (him.anyLookingFor(15) || him.nearestDistance() < 7 || age(tick) > 20 * 25) {
                vanish(him);
            }
        }

        @Override
        protected int maxTicks() {
            return 20 * 40;
        }
    }
}
