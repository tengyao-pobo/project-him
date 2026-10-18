package dev.projecthim.him.event.impl;

import dev.projecthim.him.director.EncounterMode;
import dev.projecthim.him.director.EventSpec;
import dev.projecthim.him.director.Intent;
import dev.projecthim.him.director.Stage;
import dev.projecthim.him.event.Apparition;
import dev.projecthim.him.event.EventContext;
import dev.projecthim.him.event.EventInstance;
import dev.projecthim.him.event.HorrorEvent;
import dev.projecthim.him.memory.Outcome;
import dev.projecthim.him.platform.SoundCues;
import dev.projecthim.him.sense.SpotFinder;
import java.util.List;
import java.util.Optional;

/**
 * Two footsteps right behind you. Turn around fast enough and you see him for a fraction of a
 * second. Do not turn and he was never there. Subverted: he is closer, and he does not vanish at once.
 */
final class OverShoulderEvent implements HorrorEvent {

    private final EventSpec spec = EventSpec.builder("over_shoulder", Intent.OBSERVE)
            .stage(Stage.WATCHED)
            .intensity(0.5)
            .cooldownMinutes(40)
            .weight(1.0)
            .modes(EncounterMode.PRIVATE)
            .visual()
            .requires(c -> !c.inWater() && c.speed() < 4.6)
            .fit(c -> (c.dark() || c.night() ? 1.4 : 0.8) * (c.alone() ? 1.3 : 0.7))
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
        private long seenAt = -1;
        private final int glimpseTicks;

        Instance(EventContext ctx) {
            super(ctx);
            glimpseTicks = ctx.subvert() ? 40 + ctx.him().random().nextInt(20) : 6 + ctx.him().random().nextInt(9);
        }

        @Override
        protected boolean start() {
            double min = ctx.subvert() ? 2.5 : 5;
            double max = ctx.subvert() ? 4 : 9;
            Optional<SpotFinder.Spot> spot = ctx.him().spots().behind(target(), min, max);
            if (spot.isEmpty()) {
                return false;
            }
            him = spawn(spot.get().feet(), List.of(target())).orElse(null);
            return him != null;
        }

        @Override
        protected void tick(long tick) {
            if (tickApparitions(tick)) {
                finishWithReactions();
                return;
            }
            long age = age(tick);
            if (seenAt < 0 && (age == 1 || age == 8)) {
                SoundCues.footstep(target(), him.location(), 0.55f);
            }
            Apparition.View v = him.view(target().getUniqueId());
            if (v == null) {
                finishWithReactions();
                return;
            }
            if (seenAt < 0 && v.inView()) {
                seenAt = tick;
                // A glimpse counts as noticing even if it was too short to "stare".
                setOutcome(target().getUniqueId(), Outcome.NOTICED);
            }
            if (seenAt >= 0 && tick - seenAt >= glimpseTicks) {
                vanish(him);
            } else if (seenAt < 0 && (age > 20 * 5 || v.distance() > 16)) {
                vanish(him);
            }
        }

        @Override
        protected int maxTicks() {
            return 20 * 20;
        }
    }
}
