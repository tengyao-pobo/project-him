package dev.projecthim.him.chase;

import dev.projecthim.him.util.Vec;
import java.util.random.RandomGenerator;

/**
 * Decision making of a hunt, independent of the server. The controller feeds sensor data every tick
 * and executes the returned command (where to path, how fast, whether the body is visible, whether
 * an attack is allowed).
 *
 * <p>Design rules: HIM never moves faster than the configured speeds, never teleports while a player
 * could see it, may only reposition out of sight a limited number of times, and every hunt has clear
 * ways to end in the player's favour (outrun, break line of sight, shelter, fight back, time out).
 */
public final class ChaseBrain {

    public enum Mode {
        /** Closing in unseen from the start position. */
        APPROACH,
        /** Standing still, staring, right before the hunt begins. */
        REVEAL,
        PURSUE,
        /** Short sprint faster than the player. */
        BURST,
        /** Lost sight: checking the last known position and the likely escape direction. */
        SEARCH,
        /** Turns away and vanishes, then moves unseen to cut the player off. */
        FAKE_RETREAT,
        /** Briefly stunned after being hit. */
        STAGGER,
        ENDED
    }

    public enum EndReason {
        CAUGHT, OUTRAN, LOST_SIGHT, SHELTERED, REPELLED, TIMEOUT, STUCK, TARGET_GONE, ABORTED;

        public boolean playerWon() {
            return this == OUTRAN || this == LOST_SIGHT || this == SHELTERED || this == REPELLED || this == TIMEOUT;
        }
    }

    /**
     * Sensor data for one tick.
     *
     * @param targetVelocity  in blocks per tick
     * @param targetSeesHim   whether the target could see HIM's (carrier) position right now
     * @param progressing     whether the carrier has made path progress recently
     * @param hitsTaken       total hits the player has landed on HIM during this hunt
     */
    public record Inputs(long tick, Vec him, Vec target, Vec targetVelocity, boolean himSeesTarget,
                         boolean targetSeesHim, boolean targetInWater, boolean targetSheltered,
                         boolean progressing, int hitsTaken, boolean targetDead, boolean targetGone) {
    }

    /**
     * What the controller should do this tick.
     *
     * @param speed       blocks per second, 0 means stand still
     * @param reposition  the controller may move the carrier to a hidden spot near the destination
     */
    public record Command(Mode mode, Vec destination, double speed, boolean bodyVisible, boolean attackAllowed,
                          boolean faceTarget, boolean reposition, EndReason end) {

        static Command ended(EndReason reason) {
            return new Command(Mode.ENDED, null, 0, true, false, true, false, reason);
        }
    }

    private final ChaseSettings settings;
    private final RandomGenerator random;
    private final double speedScale;

    private Mode mode;
    private long modeSince;
    private long startTick = -1;
    private long lastSeenTick;
    private Vec lastSeenPos;
    private Vec lastSeenVelocity = Vec.ZERO;
    private long burstReadyAt;
    private int lastHits;
    private boolean fakeRetreatUsed;
    private Vec retreatIntercept;
    private int repositionsUsed;
    private long stuckSince = -1;
    private long farSince = -1;
    private EndReason endReason;

    /**
     * @param startRevealed whether the hunt starts visibly near the player (skips APPROACH)
     * @param speedScale    multiplier on all speeds, derived from aggression and stage
     */
    public ChaseBrain(ChaseSettings settings, RandomGenerator random, boolean startRevealed, double speedScale) {
        this.settings = settings;
        this.random = random;
        this.speedScale = speedScale;
        this.mode = startRevealed ? Mode.REVEAL : Mode.APPROACH;
    }

    public Mode mode() {
        return mode;
    }

    public EndReason endReason() {
        return endReason;
    }

    public int repositionsUsed() {
        return repositionsUsed;
    }

    public Vec lastSeenPosition() {
        return lastSeenPos;
    }

    /** Called by the controller when it actually performed a reposition. */
    public void onRepositioned() {
        repositionsUsed++;
        stuckSince = -1;
    }

    private void enter(Mode next, long tick) {
        mode = next;
        modeSince = tick;
    }

    public Command tick(Inputs in) {
        if (mode == Mode.ENDED) {
            return Command.ended(endReason);
        }
        long tick = in.tick();
        if (startTick < 0) {
            startTick = tick;
            modeSince = tick;
            lastSeenTick = tick;
            lastSeenPos = in.target();
        }
        if (in.targetGone()) {
            return end(EndReason.TARGET_GONE);
        }
        if (in.targetDead()) {
            return end(EndReason.CAUGHT);
        }
        if (in.hitsTaken() >= settings.hitsToRepel()) {
            return end(EndReason.REPELLED);
        }
        if (tick - startTick > settings.maxDurationTicks()) {
            return end(EndReason.TIMEOUT);
        }
        if (in.himSeesTarget()) {
            lastSeenTick = tick;
            lastSeenPos = in.target();
            lastSeenVelocity = in.targetVelocity();
        }
        long sinceSeen = tick - lastSeenTick;
        double distance = in.him().distance(in.target());

        // Outran: kept beyond the escape distance for three seconds, whether or not he can see them.
        if (distance > settings.escapeDistance() && mode != Mode.FAKE_RETREAT) {
            if (farSince < 0) {
                farSince = tick;
            } else if (tick - farSince > 60) {
                return end(EndReason.OUTRAN);
            }
        } else {
            farSince = -1;
        }
        if (in.targetSheltered() && sinceSeen > 100 && mode != Mode.FAKE_RETREAT) {
            return end(EndReason.SHELTERED);
        }
        if (in.hitsTaken() > lastHits) {
            lastHits = in.hitsTaken();
            enter(Mode.STAGGER, tick);
        }

        // Stuck handling: ask for a hidden reposition a limited number of times, then give up.
        boolean moving = mode == Mode.PURSUE || mode == Mode.BURST || mode == Mode.SEARCH || mode == Mode.APPROACH;
        if (moving && !in.progressing() && distance > settings.attackRange() + 0.5) {
            if (stuckSince < 0) {
                stuckSince = tick;
            }
        } else {
            stuckSince = -1;
        }
        boolean reposition = false;
        if (stuckSince >= 0) {
            long stuck = tick - stuckSince;
            if (stuck >= 140) {
                return end(EndReason.STUCK);
            }
            if (stuck >= 50 && repositionsUsed < settings.maxRepositions() && !in.targetSeesHim()) {
                reposition = true;
            }
        }

        return switch (mode) {
            case APPROACH -> {
                if (in.targetSeesHim() || distance < 12) {
                    enter(Mode.REVEAL, tick);
                    yield stand(in, true);
                }
                yield move(Mode.APPROACH, predict(in, distance, settings.walkSpeed()), settings.walkSpeed() * 1.15,
                        true, false, reposition);
            }
            case REVEAL -> {
                if (tick - modeSince >= settings.revealTicks() || distance < 3.5) {
                    enter(Mode.PURSUE, tick);
                }
                yield stand(in, true);
            }
            case STAGGER -> {
                if (tick - modeSince >= 14) {
                    enter(Mode.PURSUE, tick);
                }
                Vec away = in.him().subtract(in.target()).horizontal().normalize();
                yield new Command(Mode.STAGGER, in.him().add(away.multiply(2)), settings.walkSpeed() * 0.6, true, false,
                        true, false, null);
            }
            case PURSUE -> pursue(in, tick, distance, sinceSeen, reposition);
            case BURST -> {
                if (tick - modeSince >= settings.burstTicks()) {
                    burstReadyAt = tick + settings.burstCooldownTicks();
                    enter(Mode.PURSUE, tick);
                }
                yield move(Mode.BURST, predict(in, distance, settings.burstSpeed()), settings.burstSpeed(), true,
                        distance <= settings.attackRange(), reposition);
            }
            case SEARCH -> {
                if (in.himSeesTarget()) {
                    enter(Mode.PURSUE, tick);
                    yield pursue(in, tick, distance, 0, reposition);
                }
                long searching = tick - modeSince;
                if (searching > settings.searchTicks()) {
                    yield end(EndReason.LOST_SIGHT);
                }
                // First the last known position, then where the player was heading.
                Vec dest = searching < settings.searchTicks() / 3 ? lastSeenPos
                        : lastSeenPos.add(lastSeenVelocity.horizontal().multiply(40));
                yield move(Mode.SEARCH, dest, settings.walkSpeed() * 1.1, true, false, reposition);
            }
            case FAKE_RETREAT -> fakeRetreat(in, tick, distance);
            case ENDED -> Command.ended(endReason);
        };
    }

    private Command pursue(Inputs in, long tick, double distance, long sinceSeen, boolean reposition) {
        if (sinceSeen > settings.loseSightTicks()) {
            enter(Mode.SEARCH, tick);
            return move(Mode.SEARCH, lastSeenPos, settings.walkSpeed() * 1.1, true, false, reposition);
        }
        if (tick >= burstReadyAt && distance > 4 && distance < 14 && in.himSeesTarget() && random.nextDouble() < 0.06) {
            enter(Mode.BURST, tick);
            return move(Mode.BURST, predict(in, distance, settings.burstSpeed()), settings.burstSpeed(), true,
                    distance <= settings.attackRange(), reposition);
        }
        if (!fakeRetreatUsed && distance > 10 && distance < 24 && in.targetSeesHim()
                && tick - startTick > 100 && random.nextDouble() < settings.fakeRetreatChance() / 120.0) {
            fakeRetreatUsed = true;
            retreatIntercept = null;
            enter(Mode.FAKE_RETREAT, tick);
            return fakeRetreat(in, tick, distance);
        }
        return move(Mode.PURSUE, predict(in, distance, settings.runSpeed()), settings.runSpeed(), true,
                distance <= settings.attackRange(), reposition);
    }

    private Command fakeRetreat(Inputs in, long tick, double distance) {
        long t = tick - modeSince;
        if (t < 40) {
            // Visibly turn and walk away.
            Vec away = in.him().subtract(in.target()).horizontal().normalize();
            return new Command(Mode.FAKE_RETREAT, in.him().add(away.multiply(10)), settings.walkSpeed(), true, false,
                    false, false, null);
        }
        if (retreatIntercept == null) {
            // Where the player will be in ~3 seconds if they keep going.
            retreatIntercept = in.target().add(in.targetVelocity().horizontal().multiply(60));
        }
        boolean arrived = in.him().horizontalDistance(retreatIntercept) < 3 || in.him().distance(in.target()) < 9;
        if ((arrived || t > 200) && !in.targetSeesHim()) {
            enter(Mode.PURSUE, tick);
            return move(Mode.PURSUE, predict(in, distance, settings.runSpeed()), settings.runSpeed(), true, false, false);
        }
        if (t > 320) {
            return end(EndReason.TIMEOUT);
        }
        // Hidden: the body is gone while the carrier legitimately walks to the intercept point.
        Vec dest = arrived ? in.him() : retreatIntercept;
        return new Command(Mode.FAKE_RETREAT, dest, arrived ? 0 : settings.runSpeed(), false, false, false, false, null);
    }

    private Command stand(Inputs in, boolean visible) {
        return new Command(mode, in.him(), 0, visible, false, true, false, null);
    }

    private Command move(Mode m, Vec dest, double speed, boolean visible, boolean attack, boolean reposition) {
        return new Command(m, dest, speed * speedScale, visible, attack, true, reposition, null);
    }

    private Command end(EndReason reason) {
        endReason = reason;
        mode = Mode.ENDED;
        return Command.ended(reason);
    }

    /** Leads the target by the time HIM needs to cover the distance, capped to one second. */
    private Vec predict(Inputs in, double distance, double speed) {
        double ticksToReach = distance / Math.max(0.1, speed / 20.0);
        double lead = Math.min(20, ticksToReach * 0.5);
        if (distance < 4) {
            return in.target();
        }
        return in.target().add(in.targetVelocity().horizontal().multiply(lead));
    }
}
