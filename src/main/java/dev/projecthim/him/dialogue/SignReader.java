package dev.projecthim.him.dialogue;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Reads what a player wrote on a sign: whether it is meant for HIM and in what tone. Simple keyword
 * matching in Chinese and English; signs are short, and a wrong guess only makes him more cryptic.
 */
public final class SignReader {

    public enum Tone {
        /** "hello", "你好", "friend". */
        GREETING,
        /** "go away", "滾", "I'm not afraid of you". */
        HOSTILE,
        /** "please leave me alone", "求你放過我". */
        PLEA,
        /** "who are you". */
        ASK_WHO,
        /** "what do you want". */
        ASK_WANT,
        /** "why". */
        ASK_WHY,
        /** Mentions him, or asks something, without a clearer tone. */
        ADDRESSED,
        /** Not for him ("storage", "Alice's house"). */
        OTHER
    }

    public record Reading(Tone tone, boolean chinese, String text) {

        public boolean addressed() {
            return tone != Tone.OTHER;
        }
    }

    private static final Pattern PLEA = Pattern.compile(
            "\\b(please|pls|plz|leave me( alone)?|let me (go|live)|stop|spare|mercy|don'?t (hurt|kill|come))\\b"
                    + "|放過|求你|求求|拜託|拜托|別再|不要再|別來|不要來|離開我|饒了|饒過|別傷害|不要傷害");
    private static final Pattern HOSTILE = Pattern.compile(
            "\\b(go away|get out|die|kill (you|u)|fuck|f\\*+k|shit|idiot|stupid|not (afraid|scared)|fight me|come at me"
                    + "|i'?ll kill|hate (you|u)|loser|noob)\\b"
                    + "|滾|走開|去死|殺了你|幹你|白痴|白癡|笨蛋|垃圾|我不怕|不怕你|來啊|有種|討厭你|恨你");
    private static final Pattern ASK_WHO = Pattern.compile(
            "\\b(who are (you|u)|who r u|what are (you|u)|who is (this|there)|who'?s there)\\b"
                    + "|你是誰|你是谁|是誰|你是什麼|你到底是|誰在那");
    private static final Pattern ASK_WANT = Pattern.compile(
            "\\b(what do (you|u) want|what (you|u) want|why are (you|u) here|what is your purpose)\\b"
                    + "|想要什麼|要什麼|你要幹嘛|你想幹嘛|你想怎樣|你要做什麼|你想做什麼");
    private static final Pattern ASK_WHY = Pattern.compile("\\b(why|how come)\\b|為什麼|为什么|為何|幹嘛跟著|為甚麼");
    private static final Pattern GREETING = Pattern.compile(
            "\\b(hi|hello|hey|hiya|yo|greetings|friend|friends|welcome|good (morning|night|evening))\\b"
                    + "|你好|您好|嗨|哈囉|哈啰|朋友|歡迎|早安|晚安");
    private static final Pattern MENTION = Pattern.compile(
            "\\b(herobrine|him|he is memory|are (you|u) (there|here|real)|i see (you|u)|i know (you|u)|is anyone)\\b"
                    + "|白眼|你在嗎|你在吗|你在哪|我看到你|我知道你|有人嗎|有人吗|你是真的");

    private SignReader() {
    }

    public static Reading read(List<String> lines) {
        String text = String.join(" ", lines).replaceAll("\\s+", " ").trim();
        String lower = text.toLowerCase(Locale.ROOT);
        boolean chinese = lower.codePoints().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN);
        return new Reading(tone(lower), chinese, text);
    }

    private static Tone tone(String t) {
        if (t.isEmpty()) {
            return Tone.OTHER;
        }
        if (PLEA.matcher(t).find()) {
            return Tone.PLEA;
        }
        if (HOSTILE.matcher(t).find()) {
            return Tone.HOSTILE;
        }
        if (ASK_WHO.matcher(t).find()) {
            return Tone.ASK_WHO;
        }
        if (ASK_WANT.matcher(t).find()) {
            return Tone.ASK_WANT;
        }
        if (ASK_WHY.matcher(t).find()) {
            return Tone.ASK_WHY;
        }
        if (GREETING.matcher(t).find()) {
            return Tone.GREETING;
        }
        if (MENTION.matcher(t).find() || t.endsWith("?") || t.endsWith("？")) {
            return Tone.ADDRESSED;
        }
        return Tone.OTHER;
    }
}
