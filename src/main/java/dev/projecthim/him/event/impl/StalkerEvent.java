package dev.projecthim.him.event.impl;

import dev.projecthim.him.director.EncounterMode;
import dev.projecthim.him.director.EventSpec;
import dev.projecthim.him.director.Intent;
import dev.projecthim.him.director.Stage;
import dev.projecthim.him.director.StageCalculator;
import dev.projecthim.him.director.Trigger;
import dev.projecthim.him.event.Apparition;
import dev.projecthim.him.event.EventContext;
import dev.projecthim.him.event.EventInstance;
import dev.projecthim.him.event.EventManager;
import dev.projecthim.him.event.HorrorEvent;
import dev.projecthim.him.memory.Personality.Trait;
import dev.projecthim.him.platform.SoundCues;
import dev.projecthim.him.sense.SpotFinder;
import java.util.List;
import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;

/**
 * A minute or two of being followed. HIM keeps a position out of the player's sight, moving from
 * cover to cover as they move; every now and then a footstep, a snapping twig, or a glimpse of him
 * peeking out. If the player keeps catching sight of him and HIM is in a hunting mood, the stalk can
 * turn into a hunt.
 */
final class StalkerEvent implements HorrorEvent {

    private final EventSpec spec = EventSpec.builder("stalker", Intent.STALK)
            .stage(Stage.WATCHED)
            .intensity(0.45)
            .cooldownMinutes(40)
            .weight(1.2)
            .modes(EncounterMode.PRIVATE)
            .visual()
            .requires(c -> !c.inWater() && !c.sheltered())
            .fit(c -> (c.alone() ? 1.5 : 0.5) * (c.night() || c.underground() ? 1.3 : 0.9) * (c.speed() > 1 ? 1.2 : 0.8))
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
        private final long duration;
        private long nextMoveAt;
        private Location hiddenAt;
        private Apparition peek;
        private long peekShownAt;
        private int peeks;
        private int sightings;
        private boolean escalate;

        Instance(EventContext ctx) {
            super(ctx);
            duration = 20L * (60 + ctx.him().random().nextInt(60));
        }

        @Override
        protected boolean start() {
            Optional<SpotFinder.Spot> spot = ctx.him().spots().hiddenOrBehind(target(), 16, 30);
            if (spot.isEmpty()) {
                return false;
            }
            hiddenAt = spot.get().feet();
            setWhere(hiddenAt);
            nextMoveAt = ctx.him().tick() + 60;
            return true;
        }

        @Override
        protected void tick(long tick) {
            tickApparitions(tick);
            if (peek != null) {
                tickPeek(tick);
                return;
            }
            if (age(tick) > duration) {
                finishWithReactions();
                return;
            }
            if (tick < nextMoveAt) {
                return;
            }
            nextMoveAt = tick + 60 + ctx.him().random().nextInt(80);
            // Keep up with the player: a new hidden position behind or beside them.
            Optional<SpotFinder.Spot> spot = ctx.him().spots().hiddenOrBehind(target(), 14, 28);
            if (spot.isEmpty()) {
                return;
            }
            hiddenAt = spot.get().feet();
            double roll = ctx.him().random().nextDouble();
            if (roll < 0.3) {
                SoundCues.footstep(target(), hiddenAt, 0.45f);
                Bukkit.getScheduler().runTaskLater(ctx.him().plugin(), () -> {
                    if (target().isOnline() && !finished()) {
                        SoundCues.footstep(target(), hiddenAt, 0.4f);
                    }
                }, 9L);
            } else if (roll < 0.45) {
                SoundCues.play(target(), hiddenAt, Sound.BLOCK_WOOD_BREAK, SoundCategory.BLOCKS, 0.35f, 1.4f);
            } else if (roll < 0.7 && peeks < 2) {
                startPeek(tick);
            }
        }

        private void startPeek(long tick) {
            Optional<SpotFinder.Spot> spot = ctx.him().spots().peek(target(), 16, 30);
            if (spot.isEmpty()) {
                return;
            }
            peek = spawn(spot.get().feet(), List.of(target())).orElse(null);
            if (peek != null) {
                peeks++;
                peekShownAt = tick;
            }
        }

        private void tickPeek(long tick) {
            if (peek.gone()) {
                peek = null;
                return;
            }
            boolean seen = peek.anyLookingFor(10);
            if (seen || tick - peekShownAt > 60 || peek.nearestDistance() < 10) {
                if (peek.anyNoticed()) {
                    sightings++;
                }
                vanish(peek);
                peek = null;
                if (sightings >= 2 && mayEscalate()) {
                    escalate = true;
                    finishWithReactions();
                }
            }
        }

        private boolean mayEscalate() {
            Stage stage = StageCalculator.stageOf(ctx.session().memory(), ctx.him().config().director());
            if (stage.ordinal() < Stage.HUNTED.ordinal() || !ctx.him().huntSlotFree()) {
                return false;
            }
            double aggression = ctx.him().memory().world().personality().get(Trait.AGGRESSION);
            return ctx.him().random().nextDouble() < aggression * 0.35 * ctx.him().config().director().aggression();
        }

        @Override
        protected void cleanup() {
            super.cleanup();
            if (escalate) {
                Player p = target();
                // Launch on the next tick, once this instance released the player's lock.
                Bukkit.getScheduler().runTask(ctx.him().plugin(), () -> {
                    if (p.isOnline() && ctx.him().sessions().eligible(p)) {
                        EventManager.LaunchResult r = ctx.him().director().launch(p, "hunt", EncounterMode.PRIVATE,
                                true, Trigger.PERIODIC);
                        ctx.him().log().debug(() -> "stalker escalated to hunt for " + p.getName() + ": " + r);
                    }
                });
            }
        }

        @Override
        protected int maxTicks() {
            return 20 * 150;
        }
    }
}
