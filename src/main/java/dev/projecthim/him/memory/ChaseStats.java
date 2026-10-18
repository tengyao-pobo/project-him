package dev.projecthim.him.memory;

import java.util.LinkedHashMap;
import java.util.Map;

/** Aggregated hunt history for one player. */
public final class ChaseStats {

    public enum EscapeMethod {
        OUTRAN, BROKE_SIGHT, WATER, SHELTER, FOUGHT, LOGGED_OUT, TIMEOUT, FLEW, TELEPORT
    }

    private int hunts;
    private int escapes;
    private int caught;
    private int repelled;
    private int slain;
    private int pearls;
    private int hitsLanded;
    private int hitsTaken;
    private long lastHuntAt;
    private LinkedHashMap<String, Integer> escapeMethods = new LinkedHashMap<>();

    public void recordStart(long now) {
        hunts++;
        lastHuntAt = now;
    }

    public void recordEscape(EscapeMethod method) {
        escapes++;
        escapeMethods.merge(method.name(), 1, Integer::sum);
    }

    public void recordCaught() {
        caught++;
    }

    public void recordRepelled() {
        repelled++;
        escapeMethods.merge(EscapeMethod.FOUGHT.name(), 1, Integer::sum);
    }

    /** The player killed him. */
    public void recordSlain() {
        slain++;
        escapeMethods.merge(EscapeMethod.FOUGHT.name(), 1, Integer::sum);
    }

    public int slain() {
        return slain;
    }

    /** The player threw an ender pearl while he hunted them. */
    public void recordPearl() {
        pearls++;
    }

    public int pearls() {
        return pearls;
    }

    public void recordHitLanded() {
        hitsLanded++;
    }

    public void recordHitTaken() {
        hitsTaken++;
    }

    /** Most frequently used escape method, if any. HIM uses this to counter the player's habit. */
    public EscapeMethod favouriteEscape() {
        String best = null;
        int bestCount = 0;
        for (Map.Entry<String, Integer> e : escapeMethods.entrySet()) {
            if (e.getValue() > bestCount) {
                best = e.getKey();
                bestCount = e.getValue();
            }
        }
        if (best == null || bestCount < 2) {
            return null;
        }
        try {
            return EscapeMethod.valueOf(best);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    public int hunts() {
        return hunts;
    }

    public int escapes() {
        return escapes;
    }

    public int caught() {
        return caught;
    }

    public int repelled() {
        return repelled;
    }

    public int hitsLanded() {
        return hitsLanded;
    }

    public int hitsTaken() {
        return hitsTaken;
    }

    public long lastHuntAt() {
        return lastHuntAt;
    }

    public Map<String, Integer> escapeMethods() {
        return Map.copyOf(escapeMethods);
    }
}
