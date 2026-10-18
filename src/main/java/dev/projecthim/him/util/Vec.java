package dev.projecthim.him.util;

/** Small immutable 3D vector used by the platform independent AI code. */
public record Vec(double x, double y, double z) {

    public static final Vec ZERO = new Vec(0, 0, 0);

    public Vec add(Vec o) {
        return new Vec(x + o.x, y + o.y, z + o.z);
    }

    public Vec subtract(Vec o) {
        return new Vec(x - o.x, y - o.y, z - o.z);
    }

    public Vec multiply(double f) {
        return new Vec(x * f, y * f, z * f);
    }

    public double length() {
        return Math.sqrt(x * x + y * y + z * z);
    }

    public double horizontalLength() {
        return Math.sqrt(x * x + z * z);
    }

    public double distance(Vec o) {
        return subtract(o).length();
    }

    public double horizontalDistance(Vec o) {
        double dx = x - o.x;
        double dz = z - o.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    public Vec horizontal() {
        return new Vec(x, 0, z);
    }

    public Vec normalize() {
        double len = length();
        return len < 1.0E-9 ? ZERO : multiply(1.0 / len);
    }

    public double dot(Vec o) {
        return x * o.x + y * o.y + z * o.z;
    }
}
