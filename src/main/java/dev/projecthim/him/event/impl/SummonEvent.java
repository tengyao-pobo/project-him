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
 * Administrator tool: HIM stands visibly near the player and stares for up to a minute (or until
 * removed with /him stop). Never chosen by the director. Useful to check the skin and visibility.
 */
final class SummonEvent implements HorrorEvent {

    static final String ID = "summon";

    private final EventSpec spec = EventSpec.builder(ID, Intent.OBSERVE)
            .stage(Stage.UNAWARE)
            .intensity(0.1)
            .cooldownMinutes(0)
            .weight(0)
            .modes(EncounterMode.PRIVATE, EncounterMode.SHARED)
            .triggers(Trigger.MANUAL)
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

        Instance(EventContext ctx) {
            super(ctx);
        }

        @Override
        protected boolean start() {
            Optional<SpotFinder.Spot> spot = ctx.him().spots().distant(target(), 6, 14, false);
            if (spot.isEmpty()) {
                spot = ctx.him().spots().behind(target(), 4, 10);
            }
            if (spot.isEmpty()) {
                return false;
            }
            var viewers = ctx.mode() == EncounterMode.SHARED ? ctx.participants() : List.of(target());
            him = spawn(spot.get().feet(), viewers).orElse(null);
            return him != null;
        }

        @Override
        protected void tick(long tick) {
            if (tickApparitions(tick) || age(tick) > 20 * 60) {
                finishWithReactions();
            }
        }

        @Override
        protected int maxTicks() {
            return 20 * 65;
        }
    }
}
