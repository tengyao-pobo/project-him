package dev.projecthim.him.event.impl;

import dev.projecthim.him.director.EncounterMode;
import dev.projecthim.him.director.EventSpec;
import dev.projecthim.him.director.Intent;
import dev.projecthim.him.director.Stage;
import dev.projecthim.him.event.EventContext;
import dev.projecthim.him.event.EventInstance;
import dev.projecthim.him.event.HorrorEvent;
import dev.projecthim.him.memory.Outcome;
import dev.projecthim.him.sense.Sight;
import dev.projecthim.him.sense.SpotFinder;
import java.util.List;
import java.util.Optional;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Rotatable;
import org.bukkit.entity.Player;

/**
 * A sign appears behind the player while they are not looking, facing them, with a message that
 * knows things: their name, how long he has known them, where they last met. Once read, it is gone
 * the next time they look. It only ever exists on that player's screen.
 */
final class FakeSignEvent implements HorrorEvent {

    private final EventSpec spec = EventSpec.builder("fake_sign", Intent.INTERFERE)
            .stage(Stage.WATCHED)
            .intensity(0.45)
            .cooldownMinutes(60)
            .weight(1.0)
            .modes(EncounterMode.PRIVATE)
            .requires(c -> !c.inWater())
            .fit(c -> c.atBase(48) ? 1.5 : 1.0)
            .build();

    @Override
    public EventSpec spec() {
        return spec;
    }

    @Override
    public EventInstance create(EventContext ctx) {
        List<String> texts = ctx.him().config().messages().signTexts();
        if (!ctx.him().fakes().enabled() || texts.isEmpty()) {
            return null;
        }
        return new Instance(ctx, texts.get(ctx.him().random().nextInt(texts.size())));
    }

    private static final class Instance extends EventInstance {
        private final String text;
        private Block block;
        private int readTicks;
        private boolean read;
        private int awayTicks;

        Instance(EventContext ctx, String text) {
            super(ctx);
            this.text = text;
        }

        @Override
        protected boolean start() {
            Player p = ctx.target();
            Optional<SpotFinder.Spot> spot = ctx.him().spots().behind(p, 3, 6);
            if (spot.isEmpty()) {
                return false;
            }
            block = spot.get().feet().getBlock();
            BlockData data = Material.OAK_SIGN.createBlockData();
            if (data instanceof Rotatable r) {
                Location sign = block.getLocation().add(0.5, 0, 0.5);
                r.setRotation(LandmarkReturnEvent.faceTowards(p.getX() - sign.getX(), p.getZ() - sign.getZ()));
            }
            setWhere(block.getLocation());
            return ctx.him().fakes().showSign(p, block, data,
                    Texts.signLines(text, p, ctx.session().memory(), ctx.him().clock().now()),
                    20 * 120, ctx.him().tick(), ctx.instanceId());
        }

        @Override
        protected void tick(long tick) {
            Player p = ctx.target();
            if (!ctx.him().fakes().isFake(p, block)) {
                // Reverted (interaction, world change): done.
                finish(read ? Outcome.NOTICED : Outcome.UNNOTICED);
                return;
            }
            Location center = block.getLocation().add(0.5, 0.5, 0.5);
            boolean looking = Sight.viewAngle(p, center) < 25 && p.getLocation().distance(center) < 12;
            if (looking) {
                awayTicks = 0;
                if (++readTicks >= 20 && !read) {
                    read = true;
                    setOutcome(p.getUniqueId(), Outcome.NOTICED);
                }
            } else if (read && ++awayTicks >= 30) {
                // Look back: it was never there.
                finish(Outcome.NOTICED);
            }
        }

        @Override
        protected void cleanup() {
            ctx.him().fakes().revertOwner(ctx.instanceId());
        }

        @Override
        protected int maxTicks() {
            return 20 * 120;
        }
    }
}
