package dev.projecthim.him.memory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Models what the player has probably learned about HIM. Repetition makes an event less effective
 * (novelty), and once a pattern is established ("he always vanishes when I walk up") HIM gains the
 * opportunity to deliberately break it.
 */
public final class ExpectationModel {

    private static final int HISTORY = 16;

    private ArrayList<String> recentEvents = new ArrayList<>();
    private ArrayList<String> recentIntents = new ArrayList<>();
    private LinkedHashMap<String, Integer> eventCounts = new LinkedHashMap<>();
    /** Times in a row the player saw HIM vanish exactly as expected. */
    private int patternStreak;
    private long lastSubversionAt;

    public void recordEvent(String eventId, String intent) {
        push(recentEvents, eventId);
        push(recentIntents, intent);
        eventCounts.merge(eventId, 1, Integer::sum);
    }

    private static void push(List<String> list, String value) {
        list.add(value);
        while (list.size() > HISTORY) {
            list.removeFirst();
        }
    }

    /**
     * Multiplier in (0,1] that discourages repeating recent events. The most recent occurrence
     * weighs most; an event never seen before gets a small bonus elsewhere.
     */
    public double novelty(String eventId, String intent) {
        double penalty = 0;
        for (int i = recentEvents.size() - 1, age = 0; i >= 0; i--, age++) {
            if (recentEvents.get(i).equals(eventId)) {
                penalty += 0.55 / (1 + age * 0.6);
            }
        }
        for (int i = recentIntents.size() - 1, age = 0; i >= 0 && age < 4; i--, age++) {
            if (recentIntents.get(i).equals(intent)) {
                penalty += 0.12 / (1 + age);
            }
        }
        double novelty = Math.exp(-penalty * 1.6);
        if (!eventCounts.containsKey(eventId)) {
            novelty *= 1.25;
        }
        return Math.min(1.25, Math.max(0.03, novelty));
    }

    public void recordPatternConfirmed() {
        patternStreak++;
    }

    public void recordSubversion(long now) {
        patternStreak = 0;
        lastSubversionAt = now;
    }

    /**
     * Chance that the next visual encounter breaks the learned pattern. Zero until a pattern has
     * been shown a few times; grows with the streak and the SUBVERSION trait, but never twice in a
     * short window so a broken expectation stays rare and meaningful.
     */
    public double subversionChance(double subversionTrait, long now, long minGapMs) {
        if (patternStreak < 3 || now - lastSubversionAt < minGapMs) {
            return 0;
        }
        return Math.min(0.6, (patternStreak - 2) * 0.07 * (0.5 + subversionTrait));
    }

    public int count(String eventId) {
        return eventCounts.getOrDefault(eventId, 0);
    }

    public int patternStreak() {
        return patternStreak;
    }

    public long lastSubversionAt() {
        return lastSubversionAt;
    }

    public List<String> recentEvents() {
        return List.copyOf(recentEvents);
    }
}
