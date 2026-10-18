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
import dev.projecthim.him.sense.Ground;
import dev.projecthim.him.sense.Sight;
import java.util.List;
import java.util.Optional;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

/**
 * Group encounter built on information asymmetry: the target sees HIM standing right behind their
 * friend. The friend only hears a single footstep behind them, and when they turn around there is
 * nothing, because for them there never was.
 */
final class BehindCompanionEvent implements HorrorEvent {

    private final EventSpec spec = EventSpec.builder("behind_companion", Intent.STALK)
            .stage(Stage.WATCHED)
            .intensity(0.55)
            .cooldownMinutes(45)
            .weight(1.6)
            .modes(EncounterMode.GROUP)
            .visual()
            .requires(c -> c.nearbyPlayers() > 0 && !c.inWater())
            .fit(c -> c.dark() || c.night() ? 1.4 : 1.0)
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
        private Player companion;
        private Apparition him;
        private boolean companionTurned;

        Instance(EventContext ctx) {
            super(ctx);
        }

        @Override
        protected boolean start() {
            Location watcher = target().getLocation();
            for (Player p : ctx.others()) {
                double d = p.getLocation().distance(watcher);
                if (d >= 5 && d <= 22) {
                    companion = p;
                    break;
                }
            }
            if (companion == null) {
                return false;
            }
            Location c = companion.getLocation();
            Vector dir = c.toVector().subtract(watcher.toVector()).setY(0);
            if (dir.lengthSquared() < 1.0E-6) {
                return false;
            }
            dir.normalize();
            Vector side = new Vector(-dir.getZ(), 0, dir.getX());
            Location eye = target().getEyeLocation();
            for (int attempt = 0; attempt < 8; attempt++) {
                double back = 2.5 + ctx.him().random().nextDouble() * 2;
                double lateral = (ctx.him().random().nextDouble() - 0.5) * 3;
                Location probe = c.clone().add(dir.clone().multiply(back)).add(side.clone().multiply(lateral));
                Optional<Location> spot = Ground.findStandable(c.getWorld(), probe.getBlockX(), c.getBlockY(), probe.getBlockZ(), 2, 3);
                if (spot.isEmpty() || Sight.exposure(eye, spot.get()) < 0.6) {
                    continue;
                }
                if (Sight.viewAngle(companion, spot.get().clone().add(0, 1.6, 0)) < 100) {
                    continue;
                }
                him = spawn(spot.get(), List.of(target())).orElse(null);
                if (him != null) {
                    return true;
                }
            }
            return false;
        }

        @Override
        protected void tick(long tick) {
            if (tickApparitions(tick)) {
                finish();
                return;
            }
            if (age(tick) == 6 && companion.isOnline()) {
                SoundCues.footstep(companion, him.location(), 0.5f);
            }
            if (companion.isOnline() && companion.getWorld() == him.location().getWorld()
                    && Sight.viewAngle(companion, him.location().clone().add(0, 1.6, 0)) < 35) {
                companionTurned = true;
            }
            boolean stared = him.anyLookingFor(25);
            boolean approached = target().getLocation().distance(him.location()) < 5;
            if (stared || approached || companionTurned && age(tick) > 20 || age(tick) > 20 * 15) {
                vanish(him);
            }
        }

        private void finish() {
            if (companion != null && companionTurned) {
                setOutcome(companion.getUniqueId(), Outcome.NOTICED);
            }
            finishWithReactions();
        }

        @Override
        protected int maxTicks() {
            return 20 * 30;
        }
    }
}
