package dev.projecthim.him.event;

import dev.projecthim.him.entity.HimBody;
import dev.projecthim.him.memory.Landmark;
import dev.projecthim.him.memory.Outcome;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * One running encounter. The event manager drives the lifecycle:
 * {@link #start()} once, {@link #tick(long)} every server tick, then exactly one
 * {@link #cleanup()} whatever the reason the instance ended. Instances must leave no trace behind:
 * every body, fake block or sound sequence they started is undone in cleanup.
 */
public abstract class EventInstance {

    protected final EventContext ctx;
    private boolean finished;
    private Outcome outcome = Outcome.UNNOTICED;
    private final Map<UUID, Outcome> perPlayer = new HashMap<>();
    private Location where;
    private final List<Landmark> landmarks = new ArrayList<>();
    private long startTick;

    protected EventInstance(EventContext ctx) {
        this.ctx = ctx;
        this.where = ctx.target().getLocation();
    }

    /**
     * Sets the encounter up. Return false if it cannot happen after all (no suitable spot); the
     * manager then refunds the budget and nothing is recorded.
     */
    protected abstract boolean start();

    /** Advances the encounter. Call {@link #finish(Outcome)} to end it. */
    protected abstract void tick(long tick);

    /** Removes everything this instance created. Called exactly once. */
    protected abstract void cleanup();

    /** Hard safety limit; the manager ends the instance (as interrupted) after this many ticks. */
    protected int maxTicks() {
        return 20 * 120;
    }

    /** Called when a participant quits, dies or changes world. Default: end if it was the target. */
    protected void onParticipantLost(UUID player, LossReason reason) {
        if (player.equals(ctx.target().getUniqueId())) {
            finish(reason == LossReason.QUIT ? Outcome.LOGGED_OUT : Outcome.INTERRUPTED);
        }
    }

    /** A player hit one of HIM's bodies that belongs to this instance. */
    protected void onBodyHit(HimBody body, Player attacker) {
        setOutcome(attacker.getUniqueId(), Outcome.ATTACKED);
    }

    /** Whether this instance owns the given body (for damage routing). */
    protected boolean owns(HimBody body) {
        return false;
    }

    public enum LossReason {
        QUIT, DEATH, WORLD_CHANGE, TELEPORT
    }

    protected final void finish(Outcome outcome) {
        if (!finished) {
            // A reaction already observed (e.g. NOTICED) is not downgraded by a weaker final outcome.
            if (rank(outcome) >= rank(this.outcome)) {
                this.outcome = outcome;
            }
            this.finished = true;
        }
    }

    /** Records a reaction without ending. The strongest reaction per player wins. */
    protected final void setOutcome(UUID player, Outcome o) {
        perPlayer.merge(player, o, (a, b) -> rank(b) > rank(a) ? b : a);
        if (player.equals(ctx.target().getUniqueId()) && rank(o) > rank(outcome)) {
            outcome = o;
        }
    }

    private static int rank(Outcome o) {
        return switch (o) {
            case UNNOTICED -> 0;
            case INTERRUPTED -> 1;
            case NOTICED -> 2;
            case APPROACHED, FLED -> 3;
            case ATTACKED -> 4;
            default -> 5;
        };
    }

    protected final void setWhere(Location where) {
        this.where = where.clone();
    }

    protected final void addLandmark(Landmark landmark) {
        landmarks.add(landmark);
    }

    public final boolean finished() {
        return finished;
    }

    public final Outcome outcome() {
        return outcome;
    }

    /** Outcome for a participant; the target's outcome is the instance outcome. */
    public final Outcome outcomeFor(UUID player) {
        if (player.equals(ctx.target().getUniqueId())) {
            return outcome;
        }
        return perPlayer.getOrDefault(player, Outcome.UNNOTICED);
    }

    public final Location where() {
        return where;
    }

    public final List<Landmark> landmarks() {
        return List.copyOf(landmarks);
    }

    public final EventContext context() {
        return ctx;
    }

    final void markStarted(long tick) {
        this.startTick = tick;
    }

    public final long startTick() {
        return startTick;
    }

    public final boolean involves(UUID player) {
        return ctx.isParticipant(player);
    }

    public final String id() {
        return ctx.spec().id();
    }

    /** Ticks since start, for subclasses. */
    protected final long age(long tick) {
        return tick - startTick;
    }
}
