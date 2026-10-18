package dev.projecthim.him.event.impl;

import dev.projecthim.him.director.EncounterMode;
import dev.projecthim.him.director.EventSpec;
import dev.projecthim.him.director.Intent;
import dev.projecthim.him.director.Stage;
import dev.projecthim.him.event.Apparition;
import dev.projecthim.him.event.EventContext;
import dev.projecthim.him.event.EventInstance;
import dev.projecthim.him.event.HorrorEvent;
import dev.projecthim.him.platform.SoundCues;
import dev.projecthim.him.sense.SpotFinder;
import java.util.List;
import java.util.Optional;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.util.Vector;

/**
 * Night, inside, a window. Someone is standing outside looking in. Look away, look back: nobody.
 * Subverted: after a long stare he backs away from the glass into the dark instead.
 */
final class WindowWatcherEvent implements HorrorEvent {

    private final EventSpec spec = EventSpec.builder("window_watcher", Intent.OBSERVE)
            .stage(Stage.WATCHED)
            .intensity(0.55)
            .cooldownMinutes(60)
            .weight(1.4)
            .modes(EncounterMode.PRIVATE, EncounterMode.SHARED)
            .visual()
            .requires(c -> c.sheltered() && (c.night() || c.thundering()))
            .fit(c -> c.atBase(40) ? 1.6 : 1.0)
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
        private boolean backingAway;

        Instance(EventContext ctx) {
            super(ctx);
        }

        @Override
        protected boolean start() {
            Optional<SpotFinder.Spot> spot = ctx.him().spots().window(target(), 10);
            if (spot.isEmpty()) {
                return false;
            }
            var viewers = ctx.mode() == EncounterMode.SHARED ? ctx.participants() : List.of(target());
            him = spawn(spot.get().feet(), viewers).orElse(null);
            if (him == null) {
                return false;
            }
            if (ctx.him().random().nextDouble() < 0.4) {
                // A single tap on the glass to draw the eye.
                Sound tap = Material.GLASS.createBlockData().getSoundGroup().getHitSound();
                SoundCues.play(viewers, him.location().clone().add(0, 1.5, 0), tap, SoundCategory.BLOCKS, 0.6f, 1.2f);
            }
            return true;
        }

        @Override
        protected void tick(long tick) {
            if (tickApparitions(tick)) {
                finishWithReactions();
                return;
            }
            if (backingAway) {
                if (!him.walking() || !him.anyInView()) {
                    vanish(him);
                }
                return;
            }
            boolean lookedAwayAfterSeeing = him.views().stream().anyMatch(v -> v.noticed() && v.awayTicksAfterNotice() >= 8);
            if (him.nearestDistance() < 4 || lookedAwayAfterSeeing) {
                vanish(him);
            } else if (ctx.subvert() && him.anyLookingFor(40)) {
                Location me = him.location();
                Vector away = me.toVector().subtract(target().getLocation().toVector()).setY(0);
                if (away.lengthSquared() > 1.0E-6) {
                    him.walkTo(me.clone().add(away.normalize().multiply(8)), 0.9, Apparition.Look.STARE);
                    backingAway = true;
                } else {
                    vanish(him);
                }
            } else if (age(tick) > 20 * 45 && !him.anyNoticed() || age(tick) > 20 * 70) {
                vanish(him);
            }
        }

        @Override
        protected int maxTicks() {
            return 20 * 90;
        }
    }
}
