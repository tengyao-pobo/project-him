package dev.projecthim.him.event.impl;

import dev.projecthim.him.director.EncounterMode;
import dev.projecthim.him.director.EventSpec;
import dev.projecthim.him.director.Intent;
import dev.projecthim.him.director.Stage;
import dev.projecthim.him.director.StageCalculator;
import dev.projecthim.him.event.Apparition;
import dev.projecthim.him.event.EventContext;
import dev.projecthim.him.event.EventInstance;
import dev.projecthim.him.event.HorrorEvent;
import dev.projecthim.him.memory.Personality.Trait;
import dev.projecthim.him.sense.SpotFinder;
import dev.projecthim.him.util.MathUtil;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * The classic: a figure far away, at the edge of vision, simply standing and watching. Stare at him
 * long enough or walk towards him and he is not there any more.
 *
 * <ul>
 *     <li>PRIVATE: only the target sees him.</li>
 *     <li>SHARED: everyone nearby sees the same figure.</li>
 *     <li>GROUP: everyone sees a figure, but each in a different place.</li>
 *     <li>Subverted: instead of vanishing when stared at, he turns and walks away, slowly, and only
 *     disappears once nobody can see him.</li>
 * </ul>
 */
final class DistantFigureEvent implements HorrorEvent {

    private final EventSpec spec = EventSpec.builder("distant_figure", Intent.OBSERVE)
            .stage(Stage.NOTICED)
            .intensity(0.35)
            .cooldownMinutes(25)
            .weight(1.3)
            .modes(EncounterMode.PRIVATE, EncounterMode.SHARED, EncounterMode.GROUP)
            .visual()
            .requires(c -> !c.underground() && !c.inWater())
            .fit(c -> (c.exposedAtNight() ? 1.6 : c.raining() ? 1.2 : 0.8) * (c.dark() ? 1.2 : 1.0))
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
        private final int stareLimit;
        private final int lifetime;
        private final Map<Apparition, Long> leaving = new HashMap<>();

        Instance(EventContext ctx) {
            super(ctx);
            stareLimit = 20 + ctx.him().random().nextInt(35);
            lifetime = 20 * (22 + ctx.him().random().nextInt(16));
        }

        @Override
        protected boolean start() {
            double boldness = ctx.him().memory().world().personality().get(Trait.BOLDNESS);
            Stage stage = StageCalculator.stageOf(ctx.session().memory(), ctx.him().config().director());
            double max = ctx.him().config().apparitions().maxDistance();
            double min = MathUtil.lerp(72, 26, boldness * (0.4 + 0.6 * stage.normalized()));
            min = Math.min(min, max - 10);
            SpotFinder spots = ctx.him().spots();
            if (ctx.mode() == EncounterMode.GROUP) {
                // Each participant sees him somewhere else.
                for (Player p : ctx.participants()) {
                    Optional<SpotFinder.Spot> spot = spots.distant(p, min, max, false);
                    spot.ifPresent(s -> spawn(s.feet(), List.of(p)));
                }
                return !apparitions.isEmpty();
            }
            Optional<SpotFinder.Spot> spot = spots.distant(target(), min, max, false);
            if (spot.isEmpty()) {
                return false;
            }
            List<Player> viewers = ctx.mode() == EncounterMode.SHARED ? ctx.participants() : List.of(target());
            return spawn(spot.get().feet(), viewers).isPresent();
        }

        @Override
        protected void tick(long tick) {
            if (tickApparitions(tick)) {
                finishWithReactions();
                return;
            }
            for (Apparition a : apparitions) {
                if (a.gone()) {
                    continue;
                }
                Long leftAt = leaving.get(a);
                if (leftAt != null) {
                    // Walking away: gone once out of everyone's view, or after a while regardless.
                    if (!a.anyInView() || tick - leftAt > 20 * 8 || !a.walking()) {
                        vanish(a);
                    }
                    continue;
                }
                boolean stared = a.anyLookingFor(stareLimit);
                boolean approached = a.nearestDistance() < 20;
                if (stared && ctx.subvert() && !approached) {
                    Location away = awayFrom(a);
                    a.walkTo(away, 1.4, Apparition.Look.WALK);
                    leaving.put(a, tick);
                } else if (stared || approached || age(tick) > lifetime) {
                    vanish(a);
                }
            }
        }

        private Location awayFrom(Apparition a) {
            Location me = a.location();
            Location viewer = target().getLocation();
            double dx = me.getX() - viewer.getX();
            double dz = me.getZ() - viewer.getZ();
            double len = Math.max(0.01, Math.sqrt(dx * dx + dz * dz));
            return me.clone().add(dx / len * 12, 0, dz / len * 12);
        }

        @Override
        protected int maxTicks() {
            return 20 * 60;
        }
    }
}
