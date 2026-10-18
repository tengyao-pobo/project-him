package dev.projecthim.him.memory;

import dev.projecthim.him.util.MathUtil;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.random.RandomGenerator;

/**
 * HIM's long lived temperament. Every world gets its own randomly seeded baseline, so two servers
 * never get exactly the same entity. Traits drift with experience but are pulled back towards the
 * baseline over time, which keeps behaviour coherent instead of random walking into extremes.
 */
public final class Personality {

    public enum Trait {
        /** Wants to look at and approach players. Drives sightings. */
        CURIOSITY,
        /** Willing to wait and watch instead of acting. Lengthens silences, favours stalking. */
        PATIENCE,
        /** Willingness to hunt and hurt. */
        AGGRESSION,
        /** Likes to copy what players do (echo sounds, mimic mining). */
        MIMICRY,
        /** Prefers players who are alone and private encounters. */
        ISOLATION,
        /** How close he dares to come and how long he stays visible. */
        BOLDNESS,
        /** Likes to break the patterns players have learned about him. */
        SUBVERSION
    }

    public static final double MIN = 0.05;
    public static final double MAX = 0.95;

    private LinkedHashMap<String, Double> traits = new LinkedHashMap<>();
    private LinkedHashMap<String, Double> baseline = new LinkedHashMap<>();
    private long lastRelaxAt;

    public Personality() {
        for (Trait t : Trait.values()) {
            traits.put(t.name(), 0.5);
            baseline.put(t.name(), 0.5);
        }
    }

    public static Personality seeded(RandomGenerator random, long now) {
        Personality p = new Personality();
        for (Trait t : Trait.values()) {
            double v = MathUtil.clamp(0.5 + (random.nextDouble() - 0.5) * 0.5, 0.2, 0.8);
            p.traits.put(t.name(), v);
            p.baseline.put(t.name(), v);
        }
        p.lastRelaxAt = now;
        return p;
    }

    public double get(Trait trait) {
        Double v = traits.get(trait.name());
        return v == null ? 0.5 : v;
    }

    public double baseline(Trait trait) {
        Double v = baseline.get(trait.name());
        return v == null ? 0.5 : v;
    }

    public void nudge(Trait trait, double delta) {
        traits.put(trait.name(), MathUtil.clamp(get(trait) + delta, MIN, MAX));
    }

    public void set(Trait trait, double value) {
        traits.put(trait.name(), MathUtil.clamp(value, MIN, MAX));
    }

    /**
     * Pulls traits back towards their baseline. {@code dailyFraction} is the share of the
     * distance removed per elapsed day.
     */
    public void relax(long now, double dailyFraction) {
        if (lastRelaxAt <= 0) {
            lastRelaxAt = now;
            return;
        }
        double days = (now - lastRelaxAt) / 86_400_000.0;
        if (days <= 0) {
            return;
        }
        double keep = Math.pow(1.0 - MathUtil.clamp(dailyFraction, 0, 1), days);
        for (Trait t : Trait.values()) {
            double base = baseline(t);
            traits.put(t.name(), MathUtil.clamp(base + (get(t) - base) * keep, MIN, MAX));
        }
        lastRelaxAt = now;
    }

    /** Repairs missing or out of range values after deserialisation. */
    public void sanitize() {
        for (Trait t : Trait.values()) {
            Double v = traits.get(t.name());
            traits.put(t.name(), v == null || !Double.isFinite(v) ? 0.5 : MathUtil.clamp(v, MIN, MAX));
            Double b = baseline.get(t.name());
            baseline.put(t.name(), b == null || !Double.isFinite(b) ? 0.5 : MathUtil.clamp(b, MIN, MAX));
        }
        traits.keySet().retainAll(baseline.keySet());
    }

    public Map<String, Double> snapshot() {
        return Map.copyOf(traits);
    }
}
