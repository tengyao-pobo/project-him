package dev.projecthim.him.event.impl;

import dev.projecthim.him.director.EncounterMode;
import dev.projecthim.him.director.EventSpec;
import dev.projecthim.him.director.Intent;
import dev.projecthim.him.director.PlayerContext;
import dev.projecthim.him.director.Stage;
import dev.projecthim.him.director.Trigger;
import dev.projecthim.him.event.Apparition;
import dev.projecthim.him.event.EventContext;
import dev.projecthim.him.event.EventInstance;
import dev.projecthim.him.event.HorrorEvent;
import dev.projecthim.him.memory.Landmark;
import dev.projecthim.him.sense.Ground;
import dev.projecthim.him.sense.Sight;
import dev.projecthim.him.sense.SpotFinder;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Rotatable;
import org.bukkit.block.BlockFace;

/**
 * Memory made visible: the player comes back to where they escaped him, died, or logged out, and he
 * is standing exactly there. Sometimes a sign is waiting too.
 */
final class LandmarkReturnEvent implements HorrorEvent {

    private final EventSpec spec = EventSpec.builder("landmark_return", Intent.OBSERVE)
            .stage(Stage.WATCHED)
            .intensity(0.45)
            .cooldownMinutes(60)
            .weight(2.0)
            .modes(EncounterMode.PRIVATE)
            .triggers(Trigger.LANDMARK, Trigger.PERIODIC)
            .visual()
            .requires(PlayerContext::nearLandmark)
            .build();

    @Override
    public EventSpec spec() {
        return spec;
    }

    @Override
    public EventInstance create(EventContext ctx) {
        Location l = ctx.target().getLocation();
        Landmark landmark = ctx.him().memory().world().nearestLandmark(ctx.target().getUniqueId(),
                l.getWorld().getName(), l.getX(), l.getY(), l.getZ(), 24);
        return landmark == null ? null : new Instance(ctx, landmark);
    }

    private static final class Instance extends VisualInstance {
        private final Landmark landmark;
        private Apparition him;

        Instance(EventContext ctx, Landmark landmark) {
            super(ctx);
            this.landmark = landmark;
        }

        @Override
        protected boolean start() {
            World w = target().getWorld();
            Location eye = target().getEyeLocation();
            Location at = Ground.findStandable(w, landmark.x(), landmark.y(), landmark.z(), 2, 3).orElse(null);
            if (at == null || at.distance(target().getLocation()) < 6 || Sight.exposure(eye, at) < 0.6) {
                Optional<SpotFinder.Spot> spot = ctx.him().spots().distant(target(), 10, 26, false);
                if (spot.isEmpty()) {
                    return false;
                }
                at = spot.get().feet();
            }
            him = spawn(at, List.of(target())).orElse(null);
            if (him == null) {
                return false;
            }
            placeSign(at);
            return true;
        }

        private void placeSign(Location himAt) {
            String kind = landmark.kind().name().toLowerCase(Locale.ROOT).replace('_', '-');
            String text = ctx.him().config().messages().landmarkText(kind);
            if (text.isBlank() || !ctx.him().fakes().enabled()) {
                return;
            }
            // In front of him, between him and the player.
            Location p = target().getLocation();
            double dx = p.getX() - himAt.getX();
            double dz = p.getZ() - himAt.getZ();
            double len = Math.max(0.01, Math.sqrt(dx * dx + dz * dz));
            Location signAt = himAt.clone().add(dx / len * 1.5, 0, dz / len * 1.5);
            Block block = signAt.getBlock();
            if (!Ground.standable(block.getWorld(), block.getX(), block.getY(), block.getZ())) {
                return;
            }
            BlockData data = Material.OAK_SIGN.createBlockData();
            if (data instanceof Rotatable r) {
                r.setRotation(faceTowards(dx, dz));
            }
            ctx.him().fakes().showSign(target(), block, data,
                    Texts.signLines(text, target(), ctx.session().memory(), ctx.him().clock().now()),
                    20 * 120, ctx.him().tick(), ctx.instanceId());
        }

        @Override
        protected void tick(long tick) {
            if (tickApparitions(tick)) {
                finishWithReactions();
                return;
            }
            boolean lookedAway = him.views().stream().anyMatch(v -> v.noticed() && v.awayTicksAfterNotice() >= 10);
            if (lookedAway || him.nearestDistance() < 5 || age(tick) > 20 * 40) {
                vanish(him);
            }
        }

        @Override
        protected int maxTicks() {
            return 20 * 60;
        }
    }

    /** Sign rotation that makes the text face the given horizontal direction. */
    static BlockFace faceTowards(double dx, double dz) {
        BlockFace[] faces = {BlockFace.SOUTH, BlockFace.SOUTH_SOUTH_WEST, BlockFace.SOUTH_WEST, BlockFace.WEST_SOUTH_WEST,
                BlockFace.WEST, BlockFace.WEST_NORTH_WEST, BlockFace.NORTH_WEST, BlockFace.NORTH_NORTH_WEST,
                BlockFace.NORTH, BlockFace.NORTH_NORTH_EAST, BlockFace.NORTH_EAST, BlockFace.EAST_NORTH_EAST,
                BlockFace.EAST, BlockFace.EAST_SOUTH_EAST, BlockFace.SOUTH_EAST, BlockFace.SOUTH_SOUTH_EAST};
        double yaw = Math.toDegrees(Math.atan2(-dx, dz));
        int index = (int) Math.round(((yaw % 360) + 360) % 360 / 22.5) & 15;
        return faces[index];
    }
}
