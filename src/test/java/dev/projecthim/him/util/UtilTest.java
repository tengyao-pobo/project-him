package dev.projecthim.him.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.projecthim.him.event.HorrorEvent;
import dev.projecthim.him.event.impl.EventCatalog;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class UtilTest {

    @Test
    void weightedPickRespectsWeightsAndIgnoresInvalid() {
        SplittableRandom r = new SplittableRandom(1);
        Map<String, Integer> counts = new HashMap<>();
        List<String> items = List.of("a", "b", "zero", "nan");
        for (int i = 0; i < 30_000; i++) {
            String s = Weighted.pick(items, x -> switch (x) {
                case "a" -> 1.0;
                case "b" -> 3.0;
                case "zero" -> 0.0;
                default -> Double.NaN;
            }, r).orElseThrow();
            counts.merge(s, 1, Integer::sum);
        }
        assertEquals(2, counts.size());
        double ratio = counts.get("b") / (double) counts.get("a");
        assertTrue(ratio > 2.7 && ratio < 3.3, "ratio " + ratio);
        assertTrue(Weighted.pick(List.of("x"), x -> 0.0, r).isEmpty());
    }

    @Test
    void yawMathMatchesMinecraftConvention() {
        assertEquals(0f, MathUtil.yawTowards(0, 0, 0, 10), 1.0E-4f);       // +Z is south, yaw 0
        assertEquals(90f, MathUtil.yawTowards(0, 0, -10, 0), 1.0E-4f);     // -X is west, yaw 90
        double[] d = MathUtil.horizontalDirection(90f);
        assertEquals(-1, d[0], 1.0E-9);
        assertEquals(0, d[1], 1.0E-9);
        assertEquals(-20f, MathUtil.yawDelta(350f, 330f), 1.0E-4f);
        assertEquals(20f, MathUtil.yawDelta(350f, 10f), 1.0E-4f);
    }

    @Test
    void catalogIsWellFormed() {
        List<HorrorEvent> events = EventCatalog.create();
        assertTrue(events.size() >= 24);
        assertEquals(events.size(), events.stream().map(HorrorEvent::id).collect(Collectors.toSet()).size());
        for (HorrorEvent e : events) {
            assertTrue(e.id().matches("[a-z_]+"), e.id());
            assertTrue(e.spec().intensity() > 0 && e.spec().intensity() <= 1, e.id());
            assertTrue(!e.spec().modes().isEmpty() && !e.spec().triggers().isEmpty(), e.id());
        }
    }
}
