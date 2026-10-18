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
import dev.projecthim.him.sense.Ground;
import dev.projecthim.him.sense.Sight;
import dev.projecthim.him.util.MathUtil;
import java.util.Optional;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * Footsteps behind the player, on the same ground they are walking on, slowly getting closer.
 * They stop the instant the player turns around. If the player stops walking, the steps stop too,
 * and then take one more step. Subverted: the last step comes from in front.
 */
final class FootstepsBehindEvent implements HorrorEvent {

    private final EventSpec spec = EventSpec.builder("footsteps_behind", Intent.PRESENCE)
            .stage(Stage.UNAWARE)
            .intensity(0.25)
            .cooldownMinutes(20)
            .weight(1.2)
            .modes(EncounterMode.PRIVATE)
            .requires(c -> !c.inWater() && c.speed() < 4.0)
            .fit(c -> (c.dark() ? 1.4 : 0.9) * (c.alone() ? 1.3 : 0.6))
            .build();

    @Override
    public EventSpec spec() {
        return spec;
    }

    @Override
    public EventInstance create(EventContext ctx) {
        return new Instance(ctx);
    }

    private static final class Instance extends AudioInstance {
        private double distance = 11;
        private long nextStepAt;
        private int pausedSteps;
        private boolean frozen;
        private long frozenUntil;
        private Location last;

        Instance(EventContext ctx) {
            super(ctx);
        }

        @Override
        protected boolean start() {
            nextStepAt = ctx.him().tick() + 10;
            return true;
        }

        @Override
        protected void tick(long tick) {
            Player p = ctx.target();
            if (last != null && Sight.viewAngle(p, last) < 75) {
                // Turned around: silence.
                setOutcome(p.getUniqueId(), Outcome.NOTICED);
                finish(Outcome.NOTICED);
                return;
            }
            if (tick < nextStepAt) {
                return;
            }
            if (frozen) {
                if (tick < frozenUntil) {
                    return;
                }
                step(p, Math.max(2.5, distance - 1));
                finish(Outcome.UNNOTICED);
                return;
            }
            boolean playerStopped = ctx.session().context() != null && ctx.session().velocity().horizontalLength() < 0.02;
            if (playerStopped && ++pausedSteps >= 2) {
                frozen = true;
                frozenUntil = tick + 30 + ctx.him().random().nextInt(30);
                return;
            }
            distance -= 0.9 + ctx.him().random().nextDouble() * 0.5;
            if (distance <= 3) {
                if (ctx.subvert()) {
                    double[] dir = MathUtil.horizontalDirection(p.getLocation().getYaw());
                    stepAt(p, p.getLocation().clone().add(dir[0] * 3, 0, dir[1] * 3));
                } else {
                    step(p, 3);
                }
                finish(Outcome.UNNOTICED);
                return;
            }
            step(p, distance);
            nextStepAt = tick + 8 + ctx.him().random().nextInt(4);
        }

        private void step(Player p, double behind) {
            Location loc = p.getLocation();
            double[] dir = MathUtil.horizontalDirection(loc.getYaw());
            Location probe = loc.clone().subtract(dir[0] * behind, 0, dir[1] * behind);
            stepAt(p, probe);
        }

        private void stepAt(Player p, Location probe) {
            Optional<Location> ground = Ground.findStandable(probe.getWorld(), probe.getBlockX(), p.getLocation().getBlockY(),
                    probe.getBlockZ(), 2, 3);
            Location at = ground.orElse(probe);
            last = at.clone().add(0, 1, 0);
            setWhere(at);
            SoundCues.footstep(p, at, 0.5f);
        }

        @Override
        protected int maxTicks() {
            return 20 * 30;
        }
    }
}
