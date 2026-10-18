package dev.projecthim.him.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.projecthim.him.dialogue.SignReader.Reading;
import dev.projecthim.him.dialogue.SignReader.Tone;
import dev.projecthim.him.dialogue.SignReplies.Effect;
import dev.projecthim.him.dialogue.SignReplies.Kind;
import dev.projecthim.him.memory.SignDialogue;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class SignDialogueTest {

    private static Tone tone(String... lines) {
        return SignReader.read(List.of(lines)).tone();
    }

    private static SignReplies.Context ctx(double aggression, double subversion, double disposition, int pleas) {
        return new SignReplies.Context(2, aggression, subversion, disposition, pleas, "Alice", 12, "Bob", "10 64 -3");
    }

    @Test
    void readsToneInChineseAndEnglish() {
        assertEquals(Tone.PLEA, tone("請你", "放過我"));
        assertEquals(Tone.PLEA, tone("please", "leave me alone"));
        assertEquals(Tone.HOSTILE, tone("滾開", "我不怕你"));
        assertEquals(Tone.HOSTILE, tone("GO AWAY"));
        assertEquals(Tone.ASK_WHO, tone("你是誰"));
        assertEquals(Tone.ASK_WHO, tone("who are you"));
        assertEquals(Tone.ASK_WANT, tone("你想要什麼"));
        assertEquals(Tone.ASK_WHY, tone("why me"));
        assertEquals(Tone.GREETING, tone("hello"));
        assertEquals(Tone.ADDRESSED, tone("Herobrine"));
        assertEquals(Tone.ADDRESSED, tone("有人在嗎？"));
        assertEquals(Tone.OTHER, tone("Storage"));
        assertEquals(Tone.OTHER, tone("this way", "to the mine"), "\"hi\" inside a word is not a greeting");
        assertEquals(Tone.OTHER, tone("", "", "", ""));
        assertTrue(SignReader.read(List.of("你好")).chinese());
        assertFalse(SignReader.read(List.of("hello")).chinese());
    }

    @Test
    void answersFitOnASignAndMatchTheLanguage() {
        SplittableRandom r = new SplittableRandom(1);
        for (Tone t : Tone.values()) {
            for (boolean zh : new boolean[] {true, false}) {
                for (int i = 0; i < 50; i++) {
                    SignReplies.Reply reply = SignReplies.choose(new Reading(t, zh, "x"), ctx(0.5, 0.5, 0, i % 4), r);
                    assertEquals(4, reply.lines().size());
                    for (String line : reply.lines()) {
                        assertTrue(line.length() <= 16, "too long: " + line);
                        if (!zh && !line.equals("Alice") && !line.equals("Bob")) {
                            assertTrue(line.chars().noneMatch(c -> c > 0x2E80), "English sign got: " + line);
                        }
                    }
                    if (reply.kind() == Kind.ERASE) {
                        assertTrue(reply.lines().stream().allMatch(String::isEmpty));
                    }
                }
            }
        }
    }

    @Test
    void provokingHimAlwaysHasAPrice() {
        SplittableRandom r = new SplittableRandom(2);
        for (int i = 0; i < 100; i++) {
            assertEquals(Effect.PROVOKED,
                    SignReplies.choose(new Reading(Tone.HOSTILE, false, "x"), ctx(0.5, 0.5, 0, 0), r).effect());
        }
        assertTrue(SignReplies.dispositionAfter(Tone.HOSTILE, 0) < 0);
        assertEquals(-1, SignReplies.dispositionAfter(Tone.HOSTILE, -0.9));
    }

    @Test
    void pleasWorkLessWhenRepeatedOrWhenHeIsAggressive() {
        assertTrue(honoured(0.3, 0, 0) > honoured(0.3, 0, 3) + 0.2, "asking again and again");
        assertTrue(honoured(0.2, 0, 0) > honoured(0.9, 0, 0) + 0.2, "aggressive HIM");
        assertTrue(honoured(0.5, 0.8, 0) > honoured(0.5, -0.8, 0), "polite players fare better");
        // A subversive HIM sometimes says yes and does not mean it.
        Set<Effect> seen = EnumSet.noneOf(Effect.class);
        SplittableRandom r = new SplittableRandom(3);
        for (int i = 0; i < 300; i++) {
            seen.add(SignReplies.choose(new Reading(Tone.PLEA, true, "x"), ctx(0.5, 0.9, 0, 1), r).effect());
        }
        assertTrue(seen.containsAll(EnumSet.of(Effect.QUIET, Effect.FALSE_QUIET, Effect.NONE)), seen.toString());
    }

    private static double honoured(double aggression, double disposition, int pleas) {
        SplittableRandom r = new SplittableRandom(4);
        int n = 0;
        for (int i = 0; i < 2000; i++) {
            if (SignReplies.choose(new Reading(Tone.PLEA, false, "x"), ctx(aggression, 0, disposition, pleas), r)
                    .effect() == Effect.QUIET) {
                n++;
            }
        }
        return n / 2000.0;
    }

    @Test
    void aRewrittenSignReplacesTheOldConversation() {
        SignDialogue d = new SignDialogue();
        d.addAnswered(new SignDialogue.Answered("w", 1, 2, 3, List.of("", "NO", "", ""), 1, 100));
        d.addPending(new SignDialogue.Pending("w", 1, 2, 3, "PLEA", false, "a", 7, 10, 0));
        d.addPending(new SignDialogue.Pending("w", 1, 2, 3, "HOSTILE", false, "b", 8, 10, 0));
        assertEquals(1, d.pending().size());
        assertEquals("HOSTILE", d.pending().getFirst().tone());
        assertTrue(d.answered().isEmpty());
        for (int i = 0; i < 10; i++) {
            d.addPending(new SignDialogue.Pending("w", i, 0, 0, "OTHER", false, "c", i, 10, 0));
        }
        assertEquals(3, d.pending().size(), "bounded");
    }
}
