package dev.projecthim.him.dialogue;

import dev.projecthim.him.Him;
import dev.projecthim.him.config.HimConfig;
import dev.projecthim.him.dialogue.SignReader.Reading;
import dev.projecthim.him.dialogue.SignReader.Tone;
import dev.projecthim.him.director.StageCalculator;
import dev.projecthim.him.director.TensionTracker;
import dev.projecthim.him.memory.EncounterRecord;
import dev.projecthim.him.memory.Personality.Trait;
import dev.projecthim.him.memory.PlayerMemory;
import dev.projecthim.him.memory.SignDialogue;
import dev.projecthim.him.player.PlayerSession;
import dev.projecthim.him.sense.Sight;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Player;

/**
 * Signs as a conversation with HIM. He reads what a player writes, waits, and answers on the same
 * sign once they are back near it and not looking at it. Only the writer sees the answer (others
 * still see the original text), and they keep seeing it for days. Nothing in the world changes.
 */
public final class SignService {

    private static final double DELIVER_RANGE = 24;
    private static final double SHOW_RANGE = 40;
    private static final long PENDING_MAX_AGE = 7L * 24 * 60 * 60_000L;
    private static final long SHOW_TICKS = 20L * 60 * 60 * 6;
    /** Beyond this the client may have unloaded the sign; it is shown again on return. */
    private static final double FORGET_RANGE = 96;

    private final Him him;

    public SignService(Him him) {
        this.him = him;
    }

    private HimConfig.Signs cfg() {
        return him.config().signs();
    }

    /** A player finished writing the front of a sign. */
    public void written(Player p, Block block, List<Component> lines) {
        if (!cfg().enabled() || !him.sessions().eligible(p)) {
            return;
        }
        PlayerSession s = him.sessions().get(p.getUniqueId());
        if (s == null) {
            return;
        }
        PlayerMemory memory = s.memory();
        if (StageCalculator.stageOf(memory, him.config().director()).ordinal() < cfg().minStage()) {
            return;
        }
        List<String> text = plain(lines);
        Reading reading = SignReader.read(text);
        if (reading.text().isEmpty() || !reading.addressed() && him.random().nextDouble() >= cfg().unaddressedChance()) {
            return;
        }
        long now = him.clock().now();
        double minutes = cfg().delayMinMinutes()
                + him.random().nextDouble() * (cfg().delayMaxMinutes() - cfg().delayMinMinutes());
        memory.dialogue().addPending(new SignDialogue.Pending(block.getWorld().getName(), block.getX(), block.getY(),
                block.getZ(), reading.tone().name(), reading.chinese(), reading.text(), hash(text), now
                + (long) (minutes * 60_000L), now));
        him.memory().markDirty(p.getUniqueId());
        him.log().debug(() -> p.getName() + " wrote to him (" + reading.tone() + "): \"" + reading.text()
                + "\", answer in " + String.format(java.util.Locale.ROOT, "%.1f", minutes) + " min");
    }

    /** Every couple of seconds: due answers, and answers the writer should keep seeing. */
    public void tick(long tick) {
        if (!cfg().enabled() || !him.config().worldEdits().fakeBlocks()) {
            return;
        }
        long now = him.clock().now();
        for (PlayerSession s : him.sessions().all()) {
            Player p = Bukkit.getPlayer(s.id());
            if (p == null) {
                continue;
            }
            SignDialogue d = s.memory().dialogue();
            boolean changed = deliverDue(p, s, d, now, tick);
            for (Iterator<SignDialogue.Answered> it = d.answered().iterator(); it.hasNext(); ) {
                SignDialogue.Answered a = it.next();
                if (now >= a.until()) {
                    it.remove();
                    changed = true;
                    continue;
                }
                Block b = blockNear(p, a.world(), a.x(), a.y(), a.z(), SHOW_RANGE);
                if (b == null) {
                    if (!p.getWorld().getName().equals(a.world())
                            || p.getLocation().distanceSquared(new Location(p.getWorld(), a.x(), a.y(), a.z()))
                            > FORGET_RANGE * FORGET_RANGE) {
                        him.fakes().forget(p, a.world(), a.x(), a.y(), a.z());
                    }
                    continue;
                }
                if (!(b.getState(false) instanceof Sign sign) || hash(sign) != a.textHash()) {
                    // Broken or rewritten: the conversation on this sign is over.
                    him.fakes().revertIfFake(p, b);
                    it.remove();
                    changed = true;
                    continue;
                }
                if (!him.fakes().isFake(p, b) && !looking(p, b)) {
                    show(p, b, a.lines(), tick);
                }
            }
            if (changed) {
                him.memory().markDirty(p.getUniqueId());
            }
        }
    }

    private boolean deliverDue(Player p, PlayerSession s, SignDialogue d, long now, long tick) {
        boolean changed = false;
        for (Iterator<SignDialogue.Pending> it = d.pending().iterator(); it.hasNext(); ) {
            SignDialogue.Pending pending = it.next();
            if (now - pending.createdAt() > PENDING_MAX_AGE) {
                it.remove();
                changed = true;
                continue;
            }
            if (now < pending.dueAt()) {
                continue;
            }
            Block b = blockNear(p, pending.world(), pending.x(), pending.y(), pending.z(), DELIVER_RANGE);
            if (b == null) {
                continue;
            }
            if (!(b.getState(false) instanceof Sign sign) || hash(sign) != pending.textHash()) {
                him.log().debug(() -> p.getName() + "'s sign at " + b.getX() + "," + b.getY() + "," + b.getZ()
                        + " is gone or was rewritten; he will not answer it");
                it.remove();
                changed = true;
                continue;
            }
            if (looking(p, b)) {
                continue;
            }
            it.remove();
            changed = true;
            answer(p, s, d, pending, b, now, tick);
        }
        return changed;
    }

    private void answer(Player p, PlayerSession s, SignDialogue d, SignDialogue.Pending pending, Block b, long now,
                        long tick) {
        PlayerMemory memory = s.memory();
        Tone tone = Tone.valueOf(pending.tone());
        var personality = him.memory().world().personality();
        SignReplies.Context c = new SignReplies.Context(
                StageCalculator.stageOf(memory, him.config().director()).ordinal(),
                personality.get(Trait.AGGRESSION) * him.config().director().aggression(),
                personality.get(Trait.SUBVERSION), d.disposition(), d.pleas(), p.getName(),
                (now - memory.firstSeen()) / (24 * 60 * 60_000L), friendName(memory), lastEncounter(memory, p));
        SignReplies.Reply reply = SignReplies.choose(new Reading(tone, pending.chinese(), pending.text()), c, him.random());
        d.setDisposition(SignReplies.dispositionAfter(tone, d.disposition()));
        long until = now + (long) (cfg().visibleDays() * 24 * 60 * 60_000L);
        d.addAnswered(new SignDialogue.Answered(pending.world(), pending.x(), pending.y(), pending.z(), reply.lines(),
                pending.textHash(), until));
        show(p, b, reply.lines(), tick);
        switch (reply.effect()) {
            case QUIET -> {
                double minutes = cfg().quietMinMinutes()
                        + him.random().nextDouble() * (cfg().quietMaxMinutes() - cfg().quietMinMinutes());
                d.setQuietUntil(now + (long) (minutes * 60_000L));
                d.countPlea(true);
            }
            case FALSE_QUIET -> {
                // He agreed. He did not mean it.
                d.countPlea(false);
                memory.adjustObsession(0.03);
            }
            case PROVOKED -> {
                d.countProvocation();
                memory.adjustObsession(0.08);
                s.tension().forcePhase(TensionTracker.Phase.PEAK, now);
            }
            case NONE -> {
                if (tone == Tone.PLEA) {
                    d.countPlea(false);
                }
                memory.adjustObsession(0.01);
            }
        }
        him.log().debug(() -> "answered " + p.getName() + "'s sign (" + tone + "): " + reply.kind() + " "
                + reply.lines() + " " + reply.effect());
    }

    private void show(Player p, Block b, List<String> lines, long tick) {
        List<Component> text = new ArrayList<>(4);
        for (String line : lines) {
            text.add(Component.text(line));
        }
        him.fakes().showSign(p, b, b.getBlockData(), text, SHOW_TICKS, tick, "signs:" + p.getUniqueId());
    }

    /** True while he should not change what the player is looking at. */
    private static boolean looking(Player p, Block b) {
        return Sight.canSee(p, b.getLocation().add(0.5, 0, 0.5), Sight.PERIPHERAL_HALF_ANGLE);
    }

    private static Block blockNear(Player p, String worldName, int x, int y, int z, double range) {
        World w = p.getWorld();
        if (!w.getName().equals(worldName) || !w.isChunkLoaded(x >> 4, z >> 4)) {
            return null;
        }
        Location l = p.getLocation();
        double dx = l.getX() - x - 0.5;
        double dy = l.getY() - y;
        double dz = l.getZ() - z - 0.5;
        return dx * dx + dy * dy + dz * dz <= range * range ? w.getBlockAt(x, y, z) : null;
    }

    private static String friendName(PlayerMemory memory) {
        UUID friend = memory.closestCompanion();
        return friend == null ? null : Bukkit.getOfflinePlayer(friend).getName();
    }

    private static String lastEncounter(PlayerMemory memory, Player p) {
        for (EncounterRecord e : memory.recentEncounters(8)) {
            if (e.world() != null && e.world().equals(p.getWorld().getName())) {
                return e.x() + " " + e.y() + " " + e.z();
            }
        }
        return null;
    }

    private static List<String> plain(List<Component> lines) {
        List<String> out = new ArrayList<>(lines.size());
        for (Component c : lines) {
            out.add(c == null ? "" : PlainTextComponentSerializer.plainText().serialize(c));
        }
        return out;
    }

    private static int hash(List<String> lines) {
        return String.join("\n", lines).strip().hashCode();
    }

    private static int hash(Sign sign) {
        return hash(plain(sign.getSide(Side.FRONT).lines()));
    }
}
