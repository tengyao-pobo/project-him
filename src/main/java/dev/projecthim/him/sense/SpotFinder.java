package dev.projecthim.him.sense;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.random.RandomGenerator;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

/**
 * Chooses where HIM should stand for a given intention: at the edge of vision far away, right
 * behind the player, half hidden behind cover, completely out of sight, or outside a window.
 * Sampling is bounded per call and never loads chunks.
 */
public final class SpotFinder {

    /**
     * A candidate position.
     *
     * @param exposure how visible the spot is from the player's eyes (0..1)
     * @param retreat  optional nearby spot that is hidden from the player (for peeking)
     */
    public record Spot(Location feet, double exposure, double distance, Location retreat) {
    }

    private static final int SAMPLES = 28;

    private final RandomGenerator random;

    public SpotFinder(RandomGenerator random) {
        this.random = random;
    }

    private List<Location> ring(Player player, double minD, double maxD, double relYaw, double spread, int samples,
                                int up, int down) {
        Location base = player.getLocation();
        World world = base.getWorld();
        float viewYaw = base.getYaw();
        List<Location> out = new ArrayList<>();
        for (int i = 0; i < samples; i++) {
            double yaw = Math.toRadians(viewYaw + relYaw + (random.nextDouble() * 2 - 1) * spread);
            double dist = minD + random.nextDouble() * (maxD - minD);
            // Minecraft yaw: direction = (-sin(yaw), cos(yaw)).
            int x = (int) Math.floor(base.getX() - Math.sin(yaw) * dist);
            int z = (int) Math.floor(base.getZ() + Math.cos(yaw) * dist);
            Ground.findStandable(world, x, base.getBlockY(), z, up, down).ifPresent(out::add);
        }
        return out;
    }

    /** A spot far away, visible, ideally at the edge of the player's vision. */
    public Optional<Spot> distant(Player player, double minD, double maxD, boolean requireSky) {
        Location eye = player.getEyeLocation();
        List<Spot> spots = new ArrayList<>();
        for (Location c : ring(player, minD, maxD, 0, 80, SAMPLES, 10, 10)) {
            World w = c.getWorld();
            if (requireSky && !Ground.skyAbove(w, c.getBlockX(), c.getBlockY() + 1, c.getBlockZ())) {
                continue;
            }
            double exposure = Sight.exposure(eye, c);
            if (exposure < 0.75) {
                continue;
            }
            spots.add(new Spot(c, exposure, c.distance(player.getLocation()), null));
        }
        return spots.stream().max(Comparator.comparingDouble(s -> distantScore(player, s)));
    }

    private double distantScore(Player player, Spot s) {
        double angle = Sight.viewAngle(player, s.feet().clone().add(0, 1.6, 0));
        // Best between 15 and 50 degrees: noticed from the corner of the eye, not dead centre.
        double periphery = angle < 15 ? 0.5 : angle <= 50 ? 1.0 : angle <= 80 ? 0.6 : 0.2;
        double rise = s.feet().getY() - player.getLocation().getY();
        double elevation = rise >= 0 && rise <= 8 ? 0.3 : 0;
        int light = Ground.light(s.feet().getWorld(), s.feet().getBlockX(), s.feet().getBlockY(), s.feet().getBlockZ());
        double dark = light <= 7 ? 0.3 : 0;
        return s.exposure() + periphery + elevation + dark + random.nextDouble() * 0.3;
    }

    /** A spot behind the player that is out of view now but visible as soon as they turn around. */
    public Optional<Spot> behind(Player player, double minD, double maxD) {
        Location eye = player.getEyeLocation();
        List<Spot> spots = new ArrayList<>();
        for (Location c : ring(player, minD, maxD, 180, 40, SAMPLES, 3, 4)) {
            if (Sight.viewAngle(player, c.clone().add(0, 1.6, 0)) < 110) {
                continue;
            }
            double exposure = Sight.exposure(eye, c);
            if (exposure >= 0.8) {
                spots.add(new Spot(c, exposure, c.distance(player.getLocation()), null));
            }
        }
        return spots.stream().max(Comparator.comparingDouble(s -> s.exposure() + random.nextDouble() * 0.2));
    }

    /** A spot next to cover: visible now, with an adjacent spot hidden from the player to step back into. */
    public Optional<Spot> peek(Player player, double minD, double maxD) {
        Location eye = player.getEyeLocation();
        List<Spot> spots = new ArrayList<>();
        for (Location c : ring(player, minD, maxD, 0, 85, SAMPLES, 6, 6)) {
            double exposure = Sight.exposure(eye, c);
            if (exposure < 0.5) {
                continue;
            }
            Location retreat = hiddenNeighbour(player, eye, c);
            if (retreat != null) {
                spots.add(new Spot(c, exposure, c.distance(player.getLocation()), retreat));
            }
        }
        return spots.stream().max(Comparator.comparingDouble(s -> coverBonus(s.feet()) + random.nextDouble() * 0.3));
    }

    private Location hiddenNeighbour(Player player, Location eye, Location c) {
        Vector toPlayer = player.getLocation().toVector().subtract(c.toVector()).setY(0);
        if (toPlayer.lengthSquared() < 1.0E-6) {
            return null;
        }
        toPlayer.normalize();
        Vector side = new Vector(-toPlayer.getZ(), 0, toPlayer.getX());
        Vector back = toPlayer.clone().multiply(-1);
        Vector[] offsets = {side.clone().multiply(1.2), side.clone().multiply(-1.2), side.clone().multiply(2.2),
                side.clone().multiply(-2.2), back.clone().multiply(1.5).add(side.clone().multiply(1.2)),
                back.clone().multiply(1.5).add(side.clone().multiply(-1.2))};
        for (Vector o : offsets) {
            Location probe = c.clone().add(o);
            Optional<Location> stand = Ground.findStandable(c.getWorld(), probe.getBlockX(), c.getBlockY(), probe.getBlockZ(), 1, 1);
            if (stand.isPresent() && Sight.exposure(eye, stand.get()) <= 0.08) {
                return stand.get();
            }
        }
        return null;
    }

    private static double coverBonus(Location feet) {
        World w = feet.getWorld();
        int bonus = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                Material m = w.getType(feet.getBlockX() + dx, feet.getBlockY() + 1, feet.getBlockZ() + dz);
                if (Tag.LOGS.isTagged(m) || m == Material.STONE || m == Material.DEEPSLATE || Tag.LEAVES.isTagged(m)) {
                    bonus++;
                }
            }
        }
        return Math.min(1.0, bonus * 0.35);
    }

    /** A spot completely hidden from the player, preferably in the given relative direction. */
    public Optional<Spot> hidden(Player player, double minD, double maxD, double relYaw, double spread) {
        Location eye = player.getEyeLocation();
        List<Spot> spots = new ArrayList<>();
        for (Location c : ring(player, minD, maxD, relYaw, spread, SAMPLES, 6, 8)) {
            double exposure = Sight.exposure(eye, c);
            if (exposure <= 0.05) {
                spots.add(new Spot(c, exposure, c.distance(player.getLocation()), null));
            }
        }
        if (spots.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(spots.get(random.nextInt(spots.size())));
    }

    /** A spot outside the player's field of view (behind them), occluded or not. */
    public Optional<Spot> outOfView(Player player, double minD, double maxD) {
        Location eye = player.getEyeLocation();
        List<Spot> spots = new ArrayList<>();
        for (Location c : ring(player, minD, maxD, 180, 70, SAMPLES, 6, 8)) {
            if (Sight.viewAngle(player, c.clone().add(0, 1.0, 0)) > 85) {
                spots.add(new Spot(c, Sight.exposure(eye, c), c.distance(player.getLocation()), null));
            }
        }
        if (spots.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(spots.get(random.nextInt(spots.size())));
    }

    /** Hidden if possible, otherwise at least out of view. */
    public Optional<Spot> hiddenOrBehind(Player player, double minD, double maxD) {
        Optional<Spot> hidden = hidden(player, minD, maxD, 180, 80);
        return hidden.isPresent() ? hidden : outOfView(player, minD, maxD);
    }

    /**
     * A spot right outside a window the player can see: glass within {@code radius}, with standable
     * ground on the far side and a clear view through it.
     */
    public Optional<Spot> window(Player player, int radius) {
        Location eye = player.getEyeLocation();
        World w = eye.getWorld();
        int ex = eye.getBlockX();
        int ey = eye.getBlockY();
        int ez = eye.getBlockZ();
        List<Spot> spots = new ArrayList<>();
        int checked = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -2; dy <= 2; dy++) {
                    int x = ex + dx;
                    int y = ey + dy;
                    int z = ez + dz;
                    if (!Ground.loaded(w, x, z)) {
                        continue;
                    }
                    Material m = w.getType(x, y, z);
                    if (!m.name().contains("GLASS") || m.name().contains("TINTED")) {
                        continue;
                    }
                    if (++checked > 64) {
                        break;
                    }
                    Location glass = new Location(w, x + 0.5, y + 0.5, z + 0.5);
                    if (Sight.viewAngle(player, glass) > 75) {
                        continue;
                    }
                    // Step out of the glass away from the player along the dominant horizontal axis.
                    int ox = 0;
                    int oz = 0;
                    if (Math.abs(dx) >= Math.abs(dz)) {
                        ox = dx >= 0 ? 1 : -1;
                    } else {
                        oz = dz >= 0 ? 1 : -1;
                    }
                    for (int feetY = y - 1; feetY >= y - 2; feetY--) {
                        if (!Ground.standable(w, x + ox, feetY, z + oz)) {
                            continue;
                        }
                        Location feet = new Location(w, x + ox + 0.5, feetY, z + oz + 0.5);
                        double exposure = Sight.exposure(eye, feet);
                        if (exposure >= 0.6) {
                            spots.add(new Spot(feet, exposure, feet.distance(player.getLocation()), null));
                        }
                        break;
                    }
                }
            }
        }
        return spots.stream().min(Comparator.comparingDouble(s -> Sight.viewAngle(player, s.feet()) + s.distance()));
    }

    /** Any standable spot near a location (used to re-place hidden carriers and bodies). */
    public Optional<Location> near(Location around, int radius) {
        World w = around.getWorld();
        for (int i = 0; i < 16; i++) {
            int x = around.getBlockX() + random.nextInt(radius * 2 + 1) - radius;
            int z = around.getBlockZ() + random.nextInt(radius * 2 + 1) - radius;
            Optional<Location> l = Ground.findStandable(w, x, around.getBlockY(), z, 4, 6);
            if (l.isPresent()) {
                return l;
            }
        }
        return Optional.empty();
    }
}
