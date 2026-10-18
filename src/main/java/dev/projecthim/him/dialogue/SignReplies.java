package dev.projecthim.him.dialogue;

import dev.projecthim.him.dialogue.SignReader.Reading;
import dev.projecthim.him.dialogue.SignReader.Tone;
import dev.projecthim.him.util.MathUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * What HIM writes back. Never chatty: a few words, sometimes only the player's own name, sometimes
 * nothing at all because he wiped their text. Answers draw on what he remembers about the player.
 * Asking him to leave may buy some quiet, or a promise he does not keep; provoking him has a price.
 */
public final class SignReplies {

    public enum Kind {
        /** His words replace the player's text. */
        ANSWER,
        /** The player's text is simply gone. */
        ERASE
    }

    public enum Effect {
        NONE,
        /** He grants the player some quiet. */
        QUIET,
        /** He says yes, and does not mean it. */
        FALSE_QUIET,
        /** He takes it personally: more obsessed, and soon. */
        PROVOKED
    }

    /**
     * What he knows when he answers.
     *
     * @param disposition    -1 (provoked again and again) to 1 (always polite), starts at 0
     * @param pleas          how often this player already asked him to leave
     * @param daysKnown      days since he first saw the player
     * @param friendName     the player's closest companion, if any
     * @param lastEncounter  where they last met ("x y z"), if remembered
     */
    public record Context(int stage, double aggression, double subversion, double disposition, int pleas,
                          String playerName, long daysKnown, String friendName, String lastEncounter) {
    }

    /** Four sign lines. */
    public record Reply(Kind kind, List<String> lines, Effect effect) {
    }

    private SignReplies() {
    }

    public static Reply choose(Reading reading, Context c, RandomGenerator r) {
        boolean zh = reading.chinese();
        return switch (reading.tone()) {
            case PLEA -> plea(c, zh, r);
            case HOSTILE -> {
                if (r.nextDouble() < 0.2) {
                    yield new Reply(Kind.ERASE, blank(), Effect.PROVOKED);
                }
                double pick = r.nextDouble();
                if (c.stage() >= 3 || c.aggression() > 0.6 ? pick < 0.5 : pick < 0.2) {
                    yield answer(Effect.PROVOKED, zh ? "很快" : "SOON");
                }
                yield pick < 0.65 ? answer(Effect.PROVOKED, zh ? "你確定嗎" : "ARE YOU", zh ? null : "SURE")
                        : answer(Effect.PROVOKED, zh ? "我會記得" : "I WILL", zh ? "你說的話" : "REMEMBER", zh ? null : "THIS");
            }
            case GREETING -> {
                double pick = r.nextDouble();
                if (pick < 0.25) {
                    yield new Reply(Kind.ERASE, blank(), Effect.NONE);
                }
                if (pick < 0.55) {
                    yield answer(Effect.NONE, c.playerName());
                }
                yield zh ? answer(Effect.NONE, "我一直", "都在")
                        : answer(Effect.NONE, "I HAVE", "ALWAYS", "BEEN HERE");
            }
            case ASK_WHO -> {
                double pick = r.nextDouble();
                if (c.daysKnown() >= 1 && pick < 0.4) {
                    yield zh ? answer(Effect.NONE, "我認識你", c.daysKnown() + " 天了")
                            : answer(Effect.NONE, "I HAVE KNOWN", "YOU FOR", c.daysKnown() + " DAYS");
                }
                if (pick < 0.7) {
                    yield zh ? answer(Effect.NONE, "我是你", "忘記的", "東西")
                            : answer(Effect.NONE, "I AM WHAT", "YOU", "FORGOT");
                }
                yield zh ? answer(Effect.NONE, "你知道", "我是誰") : answer(Effect.NONE, "YOU KNOW", "WHO I AM");
            }
            case ASK_WANT -> {
                double pick = r.nextDouble();
                if (c.friendName() != null && pick < 0.35) {
                    yield answer(Effect.NONE, c.friendName());
                }
                if (pick < 0.7) {
                    yield answer(Effect.NONE, zh ? "你" : "YOU");
                }
                yield zh ? answer(Effect.NONE, "回來") : answer(Effect.NONE, "COME", "BACK");
            }
            case ASK_WHY -> {
                if (c.lastEncounter() != null && r.nextDouble() < 0.6) {
                    yield zh ? answer(Effect.NONE, "你在", c.lastEncounter(), "看見了我")
                            : answer(Effect.NONE, "YOU SAW ME", "AT", c.lastEncounter());
                }
                yield zh ? answer(Effect.NONE, "因為你", "看見了我") : answer(Effect.NONE, "BECAUSE", "YOU SAW ME");
            }
            case ADDRESSED -> {
                double pick = r.nextDouble();
                if (pick < 0.2) {
                    yield new Reply(Kind.ERASE, blank(), Effect.NONE);
                }
                if (c.lastEncounter() != null && pick < 0.45) {
                    yield answer(Effect.NONE, c.lastEncounter());
                }
                if (pick < 0.75) {
                    yield answer(Effect.NONE, zh ? "我在" : "I AM HERE");
                }
                yield answer(Effect.NONE, zh ? "我看到了" : "I SAW IT");
            }
            case OTHER -> {
                double pick = r.nextDouble();
                if (pick < 0.5) {
                    yield new Reply(Kind.ERASE, blank(), Effect.NONE);
                }
                yield answer(Effect.NONE, pick < 0.75 ? (zh ? "我看到了" : "I SAW") : (zh ? "不是你的" : "NOT YOURS"));
            }
        };
    }

    /**
     * Asked to leave. Politeness and patience help, asking again and again does not, and an
     * aggressive HIM rarely agrees. A subversive one may say yes and mean nothing by it.
     */
    private static Reply plea(Context c, boolean zh, RandomGenerator r) {
        double honour = MathUtil.clamp(0.6 + 0.3 * c.disposition() - 0.15 * c.pleas() - 0.6 * (c.aggression() - 0.5)
                - 0.1 * Math.max(0, c.stage() - 2), 0.05, 0.9);
        double pick = r.nextDouble();
        if (pick < honour) {
            return answer(Effect.QUIET, zh ? "好" : "OK");
        }
        if (pick < honour + c.subversion() * 0.4) {
            return answer(Effect.FALSE_QUIET, zh ? "好" : "OK");
        }
        if (c.pleas() >= 2) {
            return zh ? answer(Effect.NONE, "你上次", "也這麼說") : answer(Effect.NONE, "YOU SAID", "THAT", "LAST TIME");
        }
        return answer(Effect.NONE, zh ? "不" : "NO");
    }

    /** How a sign changes his disposition towards the player. */
    public static double dispositionAfter(Tone tone, double disposition) {
        double d = switch (tone) {
            case HOSTILE -> disposition - 0.25;
            case GREETING -> disposition + 0.05;
            case PLEA -> disposition + 0.02;
            default -> disposition;
        };
        return MathUtil.clamp(d, -1, 1);
    }

    /** Centres up to three lines vertically on a four-line sign. */
    private static Reply answer(Effect effect, String... words) {
        List<String> text = new ArrayList<>();
        for (String w : words) {
            if (w != null) {
                text.add(w);
            }
        }
        List<String> lines = new ArrayList<>(List.of("", "", "", ""));
        int start = text.size() >= 3 ? 0 : 1;
        for (int i = 0; i < text.size() && start + i < 4; i++) {
            lines.set(start + i, text.get(i));
        }
        return new Reply(Kind.ANSWER, List.copyOf(lines), effect);
    }

    private static List<String> blank() {
        return List.of("", "", "", "");
    }
}
