package dev.projecthim.him.director;

import dev.projecthim.him.Him;
import dev.projecthim.him.event.EventManager;
import dev.projecthim.him.event.HorrorEvent;
import dev.projecthim.him.player.PlayerSession;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Connects the pure {@link HorrorDirector} to the running server: evaluates players on a staggered
 * schedule, handles reactive triggers, picks participants and launches the chosen event.
 */
public final class DirectorService {

    private final Him him;
    private final HorrorDirector director;
    private final GlobalBudget budget;
    private final Map<UUID, Long> pendingJoin = new HashMap<>();
    private String lastDecision = "-";

    public DirectorService(Him him, HorrorDirector director, GlobalBudget budget) {
        this.him = him;
        this.director = director;
        this.budget = budget;
    }

    public HorrorDirector director() {
        return director;
    }

    public GlobalBudget budget() {
        return budget;
    }

    public String lastDecision() {
        return lastDecision;
    }

    public void onSessionStarted(Player player, PlayerSession session) {
        long evalTicks = evaluationTicks();
        session.setNextEvaluationTick(him.tick() + evalTicks + him.random().nextLong(evalTicks));
        // The join reaction happens a little later, once the player has looked around.
        long delayMs = (long) (him.config().players().graceMinutes() * 60_000L) + 20_000L + him.random().nextLong(40_000L);
        pendingJoin.put(player.getUniqueId(), him.clock().now() + delayMs);
    }

    private long evaluationTicks() {
        return Math.max(20, Math.round(director.settings().evaluationSeconds() * 20));
    }

    public void tick(long tick) {
        if (him.paused() || !him.config().enabled()) {
            return;
        }
        long now = him.clock().now();
        long evalTicks = evaluationTicks();
        for (PlayerSession s : him.sessions().all()) {
            Long joinAt = pendingJoin.get(s.id());
            if (joinAt != null && now >= joinAt) {
                pendingJoin.remove(s.id());
                Player p = Bukkit.getPlayer(s.id());
                if (p != null) {
                    evaluate(p, s, Trigger.JOIN);
                }
                continue;
            }
            if (tick < s.nextEvaluationTick()) {
                continue;
            }
            s.setNextEvaluationTick(tick + evalTicks);
            Player p = Bukkit.getPlayer(s.id());
            if (p == null) {
                continue;
            }
            Trigger trigger = Trigger.PERIODIC;
            if (s.context() != null && s.context().nearLandmark() && now - s.lastLandmarkTriggerAt() > 10 * 60_000L) {
                trigger = Trigger.LANDMARK;
                s.setLastLandmarkTriggerAt(now);
            }
            evaluate(p, s, trigger);
        }
        pendingJoin.keySet().removeIf(id -> him.sessions().get(id) == null);
    }

    /** Reactive entry point for listeners (woke up, mined a block, ...). */
    public void react(Player player, Trigger trigger) {
        if (him.paused() || !him.config().enabled()) {
            return;
        }
        PlayerSession s = him.sessions().get(player.getUniqueId());
        if (s != null) {
            evaluate(player, s, trigger);
        }
    }

    private void evaluate(Player player, PlayerSession s, Trigger trigger) {
        if (!him.sessions().eligible(player) || him.events().isLocked(player.getUniqueId())) {
            return;
        }
        PlayerContext ctx = s.context();
        if (ctx == null) {
            return;
        }
        if (trigger == Trigger.WAKE) {
            // The context was sampled while sleeping; the player is awake now.
            ctx = ctx.toBuilder().sleeping(false).build();
        }
        DirectorInput input = new DirectorInput(ctx, s.memory(), him.memory().world(), s.tension(), trigger,
                him.clock().now(), him.performance().directorMultiplier(), him.canSpawnApparition(),
                him.huntSlotFree(), him.protection().realChangesAllowed(player.getWorld()),
                director.settings().evaluationSeconds());
        DirectorDecision decision = director.decide(input, budget, him.random());
        if (decision instanceof DirectorDecision.Hold hold) {
            if (!"quiet".equals(hold.reason())) {
                him.log().trace(() -> player.getName() + ": hold (" + hold.reason() + ")");
            }
            return;
        }
        DirectorDecision.Act act = (DirectorDecision.Act) decision;
        EventManager.LaunchResult result = launch(player, act.spec().id(), act.mode(), act.subvert(), trigger);
        lastDecision = player.getName() + " → " + act.spec().id() + " (" + act.mode() + ", " + result + ")";
        if (result != EventManager.LaunchResult.STARTED) {
            budget.refund(act.budgetCost());
            him.log().debug(() -> "could not run " + act.spec().id() + " for " + player.getName() + ": " + result);
        }
    }

    /**
     * Launches an event for a target, choosing participants for the mode. Used by the director and
     * by administrators.
     */
    public EventManager.LaunchResult launch(Player target, String eventId, EncounterMode requested, boolean subvert,
                                            Trigger trigger) {
        HorrorEvent event = him.events().get(eventId).orElse(null);
        if (event == null) {
            return EventManager.LaunchResult.UNKNOWN_EVENT;
        }
        EncounterMode mode = requested;
        List<Player> participants = new ArrayList<>();
        participants.add(target);
        if (mode != EncounterMode.PRIVATE) {
            participants.addAll(him.sessions().nearbyEligible(target, him.config().groupRadius(),
                    him.config().multiplayer().maxParticipants() - 1));
            if (participants.size() < 2) {
                if (!event.spec().supports(EncounterMode.PRIVATE) && mode == EncounterMode.GROUP) {
                    return EventManager.LaunchResult.NOT_APPLICABLE;
                }
                mode = event.spec().supports(EncounterMode.PRIVATE) ? EncounterMode.PRIVATE : mode;
            }
        }
        if (!event.spec().supports(mode)) {
            mode = event.spec().modes().iterator().next();
        }
        return him.events().launch(event, target, participants, mode, subvert, trigger);
    }
}
