package dev.projecthim.him.memory;

import dev.projecthim.him.util.MathUtil;
import java.util.ArrayList;
import java.util.List;

/**
 * Everything a player has said to HIM on signs: answers still to come, answers already written
 * (which the player keeps seeing for a while), and how the conversation has gone so far.
 */
public final class SignDialogue {

    private static final int MAX_PENDING = 3;
    private static final int MAX_ANSWERED = 16;

    /** A sign he has read and will answer. {@code textHash} detects a rewritten or replaced sign. */
    public record Pending(String world, int x, int y, int z, String tone, boolean chinese, String text, int textHash,
                          long dueAt, long createdAt) {
    }

    /** A sign he answered; the writer sees {@code lines} on it until {@code until}. */
    public record Answered(String world, int x, int y, int z, List<String> lines, int textHash, long until) {
    }

    private double disposition;
    private int signsRead;
    private int pleas;
    private int pleasHonoured;
    private int provocations;
    private long quietUntil;
    private ArrayList<Pending> pending = new ArrayList<>();
    private ArrayList<Answered> answered = new ArrayList<>();

    public void sanitize() {
        disposition = Double.isFinite(disposition) ? MathUtil.clamp(disposition, -1, 1) : 0;
        if (pending == null) {
            pending = new ArrayList<>();
        }
        if (answered == null) {
            answered = new ArrayList<>();
        }
        pending.removeIf(p -> p == null || p.world() == null || p.tone() == null);
        answered.removeIf(a -> a == null || a.world() == null || a.lines() == null || a.lines().size() != 4);
    }

    /** Reads a new sign; one at the same place replaces the older one. */
    public void addPending(Pending p) {
        pending.removeIf(o -> o.world().equals(p.world()) && o.x() == p.x() && o.y() == p.y() && o.z() == p.z());
        answered.removeIf(o -> o.world().equals(p.world()) && o.x() == p.x() && o.y() == p.y() && o.z() == p.z());
        pending.add(p);
        while (pending.size() > MAX_PENDING) {
            pending.removeFirst();
        }
        signsRead++;
    }

    public void addAnswered(Answered a) {
        answered.add(a);
        while (answered.size() > MAX_ANSWERED) {
            answered.removeFirst();
        }
    }

    public List<Pending> pending() {
        return pending;
    }

    public List<Answered> answered() {
        return answered;
    }

    public double disposition() {
        return disposition;
    }

    public void setDisposition(double disposition) {
        this.disposition = MathUtil.clamp(disposition, -1, 1);
    }

    public int signsRead() {
        return signsRead;
    }

    public int pleas() {
        return pleas;
    }

    public void countPlea(boolean honoured) {
        pleas++;
        if (honoured) {
            pleasHonoured++;
        }
    }

    public int pleasHonoured() {
        return pleasHonoured;
    }

    public void countProvocation() {
        provocations++;
    }

    public int provocations() {
        return provocations;
    }

    public long quietUntil() {
        return quietUntil;
    }

    public void setQuietUntil(long quietUntil) {
        this.quietUntil = quietUntil;
    }
}
