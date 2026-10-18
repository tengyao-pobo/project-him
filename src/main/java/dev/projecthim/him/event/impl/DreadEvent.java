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
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * The world goes quiet: music and ambience cut out, the edges of vision darken (client side only),
 * a slow heartbeat. Then it passes. Nothing happened, which is the point.
 */
final class DreadEvent implements HorrorEvent {

    private final EventSpec spec = EventSpec.builder("dread", Intent.PRESENCE)
            .stage(Stage.WATCHED)
            .intensity(0.35)
            .cooldownMinutes(40)
            .weight(0.9)
            .modes(EncounterMode.PRIVATE)
            .requires(c -> c.night() || c.underground())
            .fit(c -> c.alone() ? 1.3 : 0.6)
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
        private int beats;
        private long nextBeatAt;
        private boolean darkened;
        private Location startLocation;

        Instance(EventContext ctx) {
            super(ctx);
        }

        @Override
        protected boolean start() {
            Player p = ctx.target();
            startLocation = p.getLocation();
            p.stopSound(SoundCategory.MUSIC);
            p.stopSound(SoundCategory.AMBIENT);
            nextBeatAt = ctx.him().tick() + 40;
            return true;
        }

        @Override
        protected void tick(long tick) {
            Player p = ctx.target();
            if (tick < nextBeatAt) {
                return;
            }
            if (!darkened && !p.hasPotionEffect(PotionEffectType.DARKNESS)) {
                // Client side only: the server never applies the effect.
                p.sendPotionEffectChange(p, new PotionEffect(PotionEffectType.DARKNESS, 90, 0, false, false, false));
                darkened = true;
            }
            SoundCues.play(p, p.getLocation(), Sound.ENTITY_WARDEN_HEARTBEAT, SoundCategory.AMBIENT, 0.7f, 0.8f);
            beats++;
            nextBeatAt = tick + 22 - Math.min(8, beats);
            if (beats >= 5) {
                // Staying perfectly still through it counts as noticing.
                if (p.getLocation().distance(startLocation) < 1.5) {
                    setOutcome(p.getUniqueId(), Outcome.NOTICED);
                }
                finish(Outcome.UNNOTICED);
            }
        }

        @Override
        protected void cleanup() {
            Player p = ctx.target();
            if (darkened && p.isOnline() && !p.hasPotionEffect(PotionEffectType.DARKNESS)) {
                p.sendPotionEffectChangeRemove(p, PotionEffectType.DARKNESS);
            }
        }

        @Override
        protected int maxTicks() {
            return 20 * 20;
        }
    }
}
