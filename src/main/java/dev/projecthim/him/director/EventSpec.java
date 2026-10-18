package dev.projecthim.him.director;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

/**
 * Platform independent description of a horror event: when it may happen and how well it fits a
 * situation. The director only ever reasons about specs; the runtime implementation lives in the
 * event package.
 *
 * @param intensity  0..1, how much the event raises tension and how much budget it costs
 * @param visual     whether HIM's body appears (needs an apparition slot)
 * @param requirement hard precondition on the context
 * @param fit        soft suitability multiplier (around 1.0) for the context
 */
public record EventSpec(
        String id,
        Intent intent,
        Stage minStage,
        double intensity,
        long cooldownMs,
        double weight,
        Set<EncounterMode> modes,
        Set<Trigger> triggers,
        boolean visual,
        boolean modifiesWorld,
        Predicate<PlayerContext> requirement,
        ToDoubleFunction<PlayerContext> fit) {

    public EventSpec {
        Objects.requireNonNull(id);
        Objects.requireNonNull(intent);
        modes = Set.copyOf(modes);
        triggers = Set.copyOf(triggers);
        if (modes.isEmpty()) {
            throw new IllegalArgumentException("event " + id + " supports no encounter mode");
        }
    }

    public boolean supports(EncounterMode mode) {
        return modes.contains(mode);
    }

    public static Builder builder(String id, Intent intent) {
        return new Builder(id, intent);
    }

    public static final class Builder {
        private final String id;
        private final Intent intent;
        private Stage minStage = Stage.UNAWARE;
        private double intensity = 0.3;
        private long cooldownMs = 20 * 60_000L;
        private double weight = 1.0;
        private final EnumSet<EncounterMode> modes = EnumSet.noneOf(EncounterMode.class);
        private final EnumSet<Trigger> triggers = EnumSet.of(Trigger.PERIODIC);
        private boolean visual;
        private boolean modifiesWorld;
        private Predicate<PlayerContext> requirement = c -> true;
        private ToDoubleFunction<PlayerContext> fit = c -> 1.0;

        private Builder(String id, Intent intent) {
            this.id = id;
            this.intent = intent;
        }

        public Builder stage(Stage s) { minStage = s; return this; }
        public Builder intensity(double v) { intensity = v; return this; }
        public Builder cooldownMinutes(double minutes) { cooldownMs = (long) (minutes * 60_000L); return this; }
        public Builder weight(double v) { weight = v; return this; }
        public Builder modes(EncounterMode first, EncounterMode... rest) {
            modes.add(first);
            modes.addAll(Set.of(rest));
            return this;
        }
        /** Replaces the default {@link Trigger#PERIODIC} trigger set. */
        public Builder triggers(Trigger first, Trigger... rest) {
            triggers.clear();
            triggers.add(first);
            triggers.addAll(Set.of(rest));
            return this;
        }
        public Builder visual() { visual = true; return this; }
        public Builder modifiesWorld() { modifiesWorld = true; return this; }
        public Builder requires(Predicate<PlayerContext> p) { requirement = requirement.and(p); return this; }
        public Builder fit(ToDoubleFunction<PlayerContext> f) { fit = f; return this; }

        public EventSpec build() {
            return new EventSpec(id, intent, minStage, intensity, cooldownMs, weight, modes, triggers, visual,
                    modifiesWorld, requirement, fit);
        }
    }
}
