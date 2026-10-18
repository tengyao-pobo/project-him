package dev.projecthim.him.event.impl;

import dev.projecthim.him.director.EncounterMode;
import dev.projecthim.him.director.EventSpec;
import dev.projecthim.him.director.Intent;
import dev.projecthim.him.director.Stage;
import dev.projecthim.him.event.Apparition;
import dev.projecthim.him.event.EventContext;
import dev.projecthim.him.event.EventInstance;
import dev.projecthim.him.event.HorrorEvent;
import dev.projecthim.him.sense.SpotFinder;
import java.util.List;
import java.util.Optional;
import org.bukkit.Location;
import org.bukkit.util.Vector;

/**
 * HIM half hidden behind a tree, a rock or a corner. When he realises he has been seen he steps back
 * behind the cover, and is gone. Subverted: he steps <em>out</em> instead, in full view, and only
 * disappears when the player looks away.
 */
final class CoverPeekEvent implements HorrorEvent {

    private final EventSpec spec = EventSpec.builder("cover_peek", Intent.OBSERVE)
            .stage(Stage.NOTICED)
            .intensity(0.4)
            .cooldownMinutes(30)
            .weight(1.1)
            .modes(EncounterMode.PRIVATE, EncounterMode.SHARED)
            .visual()
            .requires(c -> !c.inWater())
            .fit(c -> (c.night() || c.dark() ? 1.4 : 0.9) * (c.alone() ? 1.2 : 1.0))
            .build();

    @Override
    public EventSpec spec() {
        return spec;
    }

    @Override
    public EventInstance create(EventContext ctx) {
        return new Instance(ctx);
    }

    private enum Phase { WATCHING, REACTING, HIDING, STEPPED_OUT }

    private static final class Instance extends VisualInstance {
        private Apparition him;
        private Location retreat;
        private Phase phase = Phase.WATCHING;
        private long phaseSince;
        private final int reactionDelay;

        Instance(EventContext ctx) {
            super(ctx);
            reactionDelay = 6 + ctx.him().random().nextInt(18);
        }

        @Override
        protected boolean start() {
            Optional<SpotFinder.Spot> spot = ctx.him().spots().peek(target(), 12, 28);
            if (spot.isEmpty()) {
                return false;
            }
            retreat = spot.get().retreat();
            var viewers = ctx.mode() == EncounterMode.SHARED ? ctx.participants() : List.of(target());
            him = spawn(spot.get().feet(), viewers).orElse(null);
            return him != null;
        }

        private void enter(Phase p, long tick) {
            phase = p;
            phaseSince = tick;
        }

        @Override
        protected void tick(long tick) {
            if (tickApparitions(tick)) {
                finishWithReactions();
                return;
            }
            long inPhase = tick - phaseSince;
            switch (phase) {
                case WATCHING -> {
                    if (him.nearestDistance() < 6) {
                        vanish(him);
                    } else if (him.anyNoticed()) {
                        enter(Phase.REACTING, tick);
                    } else if (age(tick) > 20 * 25) {
                        // Nobody looked. He leaves the way he came.
                        him.walkTo(retreat, 1.6, Apparition.Look.WALK);
                        enter(Phase.HIDING, tick);
                    }
                }
                case REACTING -> {
                    if (inPhase >= reactionDelay) {
                        if (ctx.subvert()) {
                            Location out = steppedOut();
                            him.walkTo(out, 1.2, Apparition.Look.STARE);
                            enter(Phase.STEPPED_OUT, tick);
                        } else {
                            him.walkTo(retreat, 2.4, Apparition.Look.WALK);
                            enter(Phase.HIDING, tick);
                        }
                    }
                }
                case HIDING -> {
                    if (!him.anyInView() && inPhase > 4 || inPhase > 20 * 6 || !him.walking() && inPhase > 30) {
                        vanish(him);
                    }
                }
                case STEPPED_OUT -> {
                    boolean lookedAway = him.views().stream().allMatch(v -> !v.noticed() || v.awayTicksAfterNotice() >= 8);
                    if (lookedAway || him.nearestDistance() < 5 || inPhase > 20 * 15) {
                        vanish(him);
                    }
                }
            }
        }

        /** Two steps out from cover, towards the player. */
        private Location steppedOut() {
            Location me = him.location();
            Vector dir = target().getLocation().toVector().subtract(me.toVector()).setY(0);
            if (dir.lengthSquared() < 1.0E-6) {
                return me;
            }
            dir.normalize().multiply(2.0);
            return me.clone().add(dir);
        }

        @Override
        protected int maxTicks() {
            return 20 * 60;
        }
    }
}
