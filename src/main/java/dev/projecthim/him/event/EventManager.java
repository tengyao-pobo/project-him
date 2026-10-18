package dev.projecthim.him.event;

import dev.projecthim.him.Him;
import dev.projecthim.him.director.EncounterMode;
import dev.projecthim.him.director.Intent;
import dev.projecthim.him.director.Learning;
import dev.projecthim.him.director.Trigger;
import dev.projecthim.him.entity.HimBody;
import dev.projecthim.him.memory.EncounterRecord;
import dev.projecthim.him.memory.Landmark;
import dev.projecthim.him.memory.Outcome;
import dev.projecthim.him.memory.PlayerMemory;
import dev.projecthim.him.player.PlayerSession;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * Central coordinator of running encounters.
 *
 * <ul>
 *     <li>A player takes part in at most one encounter at a time (locks), so encounters never
 *     overlap or contradict each other for the same person.</li>
 *     <li>Every instance is isolated: an exception ends that instance (with full cleanup) instead of
 *     breaking the tick loop.</li>
 *     <li>Every instance has a hard time limit.</li>
 *     <li>Outcomes are written to memory and fed to {@link Learning} when an instance ends.</li>
 * </ul>
 */
public final class EventManager {

    public enum LaunchResult {
        STARTED, UNKNOWN_EVENT, PARTICIPANT_BUSY, NOT_APPLICABLE, NO_SPOT, ERROR
    }

    private final Him him;
    private final Map<String, HorrorEvent> events = new LinkedHashMap<>();
    private final List<EventInstance> active = new ArrayList<>();
    private final Map<UUID, EventInstance> locks = new HashMap<>();
    private long counter;

    public EventManager(Him him) {
        this.him = him;
    }

    public void register(HorrorEvent event) {
        if (events.putIfAbsent(event.id(), event) != null) {
            throw new IllegalStateException("duplicate event id " + event.id());
        }
    }

    public Collection<HorrorEvent> all() {
        return events.values();
    }

    public Optional<HorrorEvent> get(String id) {
        return Optional.ofNullable(events.get(id));
    }

    public boolean isLocked(UUID player) {
        return locks.containsKey(player);
    }

    public EventInstance instanceOf(UUID player) {
        return locks.get(player);
    }

    public List<EventInstance> active() {
        return List.copyOf(active);
    }

    public int activeHunts() {
        int n = 0;
        for (EventInstance i : active) {
            if (i.context().spec().intent() == Intent.HUNT) {
                n++;
            }
        }
        return n;
    }

    public LaunchResult launch(HorrorEvent event, Player target, List<Player> participants, EncounterMode mode,
                               boolean subvert, Trigger trigger) {
        for (Player p : participants) {
            if (locks.containsKey(p.getUniqueId())) {
                return LaunchResult.PARTICIPANT_BUSY;
            }
        }
        String instanceId = event.id() + "#" + (++counter);
        EventContext ctx = new EventContext(him, event.spec(), instanceId, target, List.copyOf(participants), mode,
                subvert, trigger);
        EventInstance instance;
        try {
            instance = event.create(ctx);
        } catch (RuntimeException e) {
            him.log().error("Event " + event.id() + " failed to initialise", e);
            return LaunchResult.ERROR;
        }
        if (instance == null) {
            return LaunchResult.NOT_APPLICABLE;
        }
        instance.markStarted(him.tick());
        boolean started;
        try {
            started = instance.start();
        } catch (RuntimeException e) {
            him.log().error("Event " + event.id() + " failed to start", e);
            safeCleanup(instance);
            return LaunchResult.ERROR;
        }
        if (!started) {
            safeCleanup(instance);
            return LaunchResult.NO_SPOT;
        }
        active.add(instance);
        long now = him.clock().now();
        for (Player p : participants) {
            locks.put(p.getUniqueId(), instance);
            PlayerSession s = him.sessions().get(p.getUniqueId());
            if (s != null && trigger != Trigger.MANUAL) {
                s.tension().onEvent(event.spec().intensity(), now);
                s.memory().markEventTime(event.id(), now);
            }
        }
        him.log().debug(() -> "started " + instanceId + " for " + target.getName() + " mode=" + mode
                + (subvert ? " (subverted)" : "") + " trigger=" + trigger
                + (participants.size() > 1 ? " participants=" + participants.stream().map(Player::getName).toList() : ""));
        return LaunchResult.STARTED;
    }

    public void tick(long tick) {
        if (active.isEmpty()) {
            return;
        }
        for (EventInstance instance : List.copyOf(active)) {
            if (!instance.finished()) {
                if (instance.age(tick) > instance.maxTicks()) {
                    him.log().debug(() -> instance.context().instanceId() + " hit its time limit");
                    instance.finish(Outcome.INTERRUPTED);
                } else {
                    try {
                        instance.tick(tick);
                    } catch (RuntimeException e) {
                        him.log().error("Event " + instance.context().instanceId() + " crashed and was stopped", e);
                        instance.finish(Outcome.INTERRUPTED);
                    }
                }
            }
            if (instance.finished()) {
                complete(instance);
            }
        }
    }

    private void complete(EventInstance instance) {
        if (!active.remove(instance)) {
            return;
        }
        safeCleanup(instance);
        locks.values().removeIf(i -> i == instance);
        EventContext ctx = instance.context();
        if (ctx.trigger() == Trigger.MANUAL) {
            // Administrator tests never shape HIM's memory or personality.
            him.log().debug(() -> "ended manual " + ctx.instanceId() + " outcome=" + instance.outcome());
            return;
        }
        long now = him.clock().now();
        Location where = instance.where();
        boolean first = true;
        for (Player p : ctx.participants()) {
            PlayerSession s = him.sessions().get(p.getUniqueId());
            PlayerMemory memory = s != null ? s.memory() : him.memory().get(p.getUniqueId());
            if (memory == null) {
                continue;
            }
            Outcome outcome = instance.outcomeFor(p.getUniqueId());
            memory.recordEncounter(new EncounterRecord(now, ctx.spec().id(), ctx.mode().name(),
                    where.getWorld().getName(), where.getBlockX(), where.getBlockY(), where.getBlockZ(), outcome));
            if (first) {
                // HIM's personality learns once per encounter, from the target's reaction.
                Learning.apply(memory, him.memory().world(), ctx.spec(), outcome, ctx.subvert(), now);
                first = false;
            } else if (ctx.spec().visual()) {
                memory.behavior().recordReaction(outcome);
                memory.expectations().recordEvent(ctx.spec().id(), ctx.spec().intent().name());
            }
            him.memory().markDirty(p.getUniqueId());
        }
        for (Landmark l : instance.landmarks()) {
            him.memory().world().addLandmark(l);
        }
        him.memory().markWorldDirty();
        him.log().debug(() -> "ended " + ctx.instanceId() + " outcome=" + instance.outcome()
                + " after " + (him.tick() - instance.startTick()) + " ticks");
    }

    private void safeCleanup(EventInstance instance) {
        try {
            instance.cleanup();
        } catch (RuntimeException e) {
            him.log().error("Cleanup of " + instance.context().instanceId() + " failed", e);
        }
        // Belt and braces: no fake block of this instance may survive it.
        him.fakes().revertOwner(instance.context().instanceId());
    }

    /** A participant left, died or moved away: let the instance react, then finalise if it ended. */
    public void participantLost(UUID player, EventInstance.LossReason reason) {
        EventInstance instance = locks.get(player);
        if (instance == null) {
            return;
        }
        try {
            instance.onParticipantLost(player, reason);
        } catch (RuntimeException e) {
            him.log().error("Event " + instance.context().instanceId() + " failed handling a lost participant", e);
            instance.finish(Outcome.INTERRUPTED);
        }
        if (instance.finished()) {
            locks.remove(player);
            complete(instance);
        } else if (!instance.retains(player)) {
            locks.remove(player);
        }
    }

    /** Whether the player is being hunted right now. */
    public boolean isHunted(UUID player) {
        EventInstance instance = locks.get(player);
        return instance != null && !instance.finished() && instance.context().spec().intent() == dev.projecthim.him.director.Intent.HUNT
                && instance.context().target().getUniqueId().equals(player);
    }

    public void bodyHit(HimBody body, Player attacker, double damage) {
        for (EventInstance instance : List.copyOf(active)) {
            if (instance.owns(body)) {
                try {
                    instance.onBodyHit(body, attacker, damage);
                } catch (RuntimeException e) {
                    him.log().error("Event " + instance.context().instanceId() + " failed handling a hit", e);
                    instance.finish(Outcome.INTERRUPTED);
                }
                if (instance.finished()) {
                    complete(instance);
                }
                return;
            }
        }
    }

    /** Ends every running encounter immediately with full cleanup. */
    public void cancelAll(Outcome outcome) {
        for (EventInstance instance : List.copyOf(active)) {
            instance.finish(outcome);
            complete(instance);
        }
    }

    /** Ends encounters that involve the player. */
    public int cancelFor(UUID player) {
        int n = 0;
        for (EventInstance instance : List.copyOf(active)) {
            if (instance.involves(player)) {
                instance.finish(Outcome.INTERRUPTED);
                complete(instance);
                n++;
            }
        }
        return n;
    }

    /** Ends hunts (performance emergency). */
    public void cancelHunts() {
        for (EventInstance instance : List.copyOf(active)) {
            if (instance.context().spec().intent() == Intent.HUNT) {
                instance.finish(Outcome.INTERRUPTED);
                complete(instance);
            }
        }
    }
}
