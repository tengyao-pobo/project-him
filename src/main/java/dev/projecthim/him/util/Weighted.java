package dev.projecthim.him.util;

import java.util.List;
import java.util.Optional;
import java.util.function.ToDoubleFunction;
import java.util.random.RandomGenerator;

/** Weighted random selection helpers. Non-positive and non-finite weights are treated as zero. */
public final class Weighted {

    private Weighted() {
    }

    public static <T> Optional<T> pick(List<T> items, ToDoubleFunction<T> weight, RandomGenerator random) {
        double total = 0;
        double[] weights = new double[items.size()];
        for (int i = 0; i < items.size(); i++) {
            double w = weight.applyAsDouble(items.get(i));
            weights[i] = Double.isFinite(w) && w > 0 ? w : 0;
            total += weights[i];
        }
        if (total <= 0) {
            return Optional.empty();
        }
        double roll = random.nextDouble() * total;
        for (int i = 0; i < weights.length; i++) {
            roll -= weights[i];
            if (roll < 0 && weights[i] > 0) {
                return Optional.of(items.get(i));
            }
        }
        // Floating point slack: return the last positive entry.
        for (int i = weights.length - 1; i >= 0; i--) {
            if (weights[i] > 0) {
                return Optional.of(items.get(i));
            }
        }
        return Optional.empty();
    }
}
