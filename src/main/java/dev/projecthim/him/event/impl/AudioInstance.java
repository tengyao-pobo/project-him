package dev.projecthim.him.event.impl;

import dev.projecthim.him.event.EventContext;
import dev.projecthim.him.event.EventInstance;
import dev.projecthim.him.memory.Outcome;
import dev.projecthim.him.sense.Sight;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * Base for sound-only encounters. Since a sound cannot be "seen", a reaction is inferred: turning
 * towards the source for a moment counts as noticing it, closing the distance to it as approaching.
 */
abstract class AudioInstance extends EventInstance {

    private final Map<UUID, Integer> facingTicks = new HashMap<>();
    private final Map<UUID, Location> startPosition = new HashMap<>();

    protected AudioInstance(EventContext ctx) {
        super(ctx);
    }

    /** Call every tick with the current sound source for each listener. */
    protected void observe(Player listener, Location source) {
        if (listener.getWorld() != source.getWorld()) {
            return;
        }
        UUID id = listener.getUniqueId();
        startPosition.putIfAbsent(id, listener.getLocation());
        if (Sight.viewAngle(listener, source) < 30) {
            int t = facingTicks.merge(id, 1, Integer::sum);
            if (t >= 8) {
                setOutcome(id, Outcome.NOTICED);
            }
        } else {
            facingTicks.put(id, 0);
        }
        if (approached(listener, source, 5)) {
            setOutcome(id, Outcome.APPROACHED);
        }
    }

    /**
     * Whether the listener walked towards the source by at least {@code by} blocks. Measured from the
     * listener's own starting point, so a source that moves closer does not count as approaching.
     */
    protected boolean approached(Player listener, Location source, double by) {
        Location start = startPosition.get(listener.getUniqueId());
        if (start == null || start.getWorld() != source.getWorld() || listener.getWorld() != source.getWorld()) {
            return false;
        }
        return start.distance(source) - listener.getLocation().distance(source) >= by;
    }

    @Override
    protected void cleanup() {
        // Sounds leave nothing behind.
    }
}
