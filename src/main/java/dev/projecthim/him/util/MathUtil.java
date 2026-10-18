package dev.projecthim.him.util;

public final class MathUtil {

    private MathUtil() {
    }

    public static double clamp(double value, double min, double max) {
        return value < min ? min : Math.min(value, max);
    }

    public static int clamp(int value, int min, int max) {
        return value < min ? min : Math.min(value, max);
    }

    public static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    /** Exponential decay factor for {@code elapsedMs} with the given half life. */
    public static double halfLifeFactor(long elapsedMs, long halfLifeMs) {
        if (elapsedMs <= 0 || halfLifeMs <= 0) {
            return 1.0;
        }
        return Math.pow(0.5, (double) elapsedMs / (double) halfLifeMs);
    }

    /** Exponential moving average step. */
    public static double ema(double previous, double sample, double alpha) {
        return previous + (sample - previous) * alpha;
    }

    /** Smallest signed difference between two yaw angles in degrees, in [-180, 180). */
    public static float yawDelta(float from, float to) {
        float d = (to - from) % 360f;
        if (d >= 180f) {
            d -= 360f;
        } else if (d < -180f) {
            d += 360f;
        }
        return d;
    }

    /** Unit horizontal direction (x, z) a Minecraft yaw points to. Never zero, unlike a pitched look vector. */
    public static double[] horizontalDirection(float yaw) {
        double rad = Math.toRadians(yaw);
        return new double[] {-Math.sin(rad), Math.cos(rad)};
    }

    /** Minecraft yaw (degrees) that faces from (x1,z1) towards (x2,z2). */
    public static float yawTowards(double x1, double z1, double x2, double z2) {
        return (float) Math.toDegrees(Math.atan2(-(x2 - x1), z2 - z1));
    }

    /** Minecraft pitch (degrees) that faces from a point towards another. */
    public static float pitchTowards(double x1, double y1, double z1, double x2, double y2, double z2) {
        double dx = x2 - x1;
        double dz = z2 - z1;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        return (float) -Math.toDegrees(Math.atan2(y2 - y1, horizontal));
    }
}
