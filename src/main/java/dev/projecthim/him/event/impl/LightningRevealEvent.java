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
 * A thunderstorm. With every flash of lightning he is standing a little closer, and between the
 * flashes there is nothing. The lightning itself is a harmless visual effect that everyone nearby
 * sees; in PRIVATE mode only the target sees who stands in it.
 */
final class LightningRevealEvent implements HorrorEvent {

    private static final double[] DISTANCES = {38, 26, 15};

    private final EventSpec spec = EventSpec.builder("lightning_reveal", Intent.OBSERVE)
            .stage(Stage.WATCHED)
            .intensity(0.6)
            .cooldownMinutes(45)
            .weight(2.0)
            .modes(EncounterMode.PRIVATE, EncounterMode.SHARED)
            .visual()
            .requires(c -> c.thundering() && c.skyVisible() && !c.underground())
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
        private int flash;
        private long nextFlashAt;
        private long concealAt = -1;

        Instance(EventContext ctx) {
            super(ctx);
        }

        @Override
        protected boolean start() {
            Optional<SpotFinder.Spot> spot = ctx.him().spots().distant(target(), DISTANCES[0] - 5, DISTANCES[0] + 5, true);
            if (spot.isEmpty()) {
                return false;
            }
            var viewers = ctx.mode() == EncounterMode.SHARED ? ctx.participants() : List.of(target());
            him = spawn(spot.get().feet(), viewers, true).orElse(null);
            if (him == null) {
                return false;
            }
            nextFlashAt = ctx.him().tick() + 10;
            return true;
        }

        @Override
        protected void tick(long tick) {
            if (tickApparitions(tick)) {
                finishWithReactions();
                return;
            }
            if (concealAt >= 0 && tick >= concealAt) {
                collectReactions();
                him.conceal();
                concealAt = -1;
                if (flash >= DISTANCES.length) {
                    finishWithReactions();
                    return;
                }
            }
            if (tick >= nextFlashAt && concealAt < 0) {
                if (flash > 0 && !moveCloser()) {
                    finishWithReactions();
                    return;
                }
                strike();
                him.reveal();
                concealAt = tick + 12 + ctx.him().random().nextInt(8);
                nextFlashAt = tick + 40 + ctx.him().random().nextInt(30);
                flash++;
            }
        }

        private boolean moveCloser() {
            Location me = him.location();
            Vector toPlayer = target().getLocation().toVector().subtract(me.toVector()).setY(0);
            double current = toPlayer.length();
            double wanted = DISTANCES[Math.min(flash, DISTANCES.length - 1)];
            if (current < wanted + 2 || current < 1.0E-3) {
                return true;
            }
            Location probe = me.clone().add(toPlayer.normalize().multiply(current - wanted));
            Optional<Location> ground = dev.projecthim.him.sense.Ground.findStandable(me.getWorld(), probe.getBlockX(),
                    probe.getBlockY(), probe.getBlockZ(), 6, 6);
            if (ground.isEmpty()) {
                return false;
            }
            him.relocate(ground.get());
            return true;
        }

        private void strike() {
            Location me = him.location();
            double angle = ctx.him().random().nextDouble() * Math.PI * 2;
            Location bolt = me.clone().add(Math.cos(angle) * 9, 0, Math.sin(angle) * 9);
            if (!me.getWorld().isChunkLoaded(bolt.getBlockX() >> 4, bolt.getBlockZ() >> 4)) {
                bolt = me.clone();
            }
            bolt.setY(me.getWorld().getHighestBlockYAt(bolt) + 1);
            me.getWorld().strikeLightningEffect(bolt);
        }

        @Override
        protected int maxTicks() {
            return 20 * 30;
        }
    }
}
