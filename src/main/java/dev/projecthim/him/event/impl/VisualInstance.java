package dev.projecthim.him.event.impl;

import dev.projecthim.him.entity.HimBody;
import dev.projecthim.him.event.Apparition;
import dev.projecthim.him.event.EventContext;
import dev.projecthim.him.event.EventInstance;
import dev.projecthim.him.memory.Outcome;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * Base for encounters in which HIM is seen. Owns the apparitions, routes hits, removes everything on
 * cleanup and turns what viewers did into outcomes.
 */
abstract class VisualInstance extends EventInstance {

    protected final List<Apparition> apparitions = new ArrayList<>();

    protected VisualInstance(EventContext ctx) {
        super(ctx);
    }

    protected Optional<Apparition> spawn(Location feet, Collection<? extends Player> viewers) {
        return spawn(feet, viewers, false);
    }

    protected Optional<Apparition> spawn(Location feet, Collection<? extends Player> viewers, boolean concealed) {
        return spawn(feet, viewers, concealed, null);
    }

    protected Optional<Apparition> spawn(Location feet, Collection<? extends Player> viewers, boolean concealed,
                                         ResolvableProfile profile) {
        Optional<Apparition> a = Apparition.spawn(ctx.him(), feet, viewers, concealed, profile);
        a.ifPresent(apparition -> {
            apparitions.add(apparition);
            setWhere(feet);
        });
        return a;
    }

    protected Player target() {
        return ctx.target();
    }

    protected static Player online(UUID id) {
        return Bukkit.getPlayer(id);
    }

    /** Ticks all apparitions; returns true if every apparition is gone. */
    protected boolean tickApparitions(long tick) {
        boolean allGone = true;
        for (Apparition a : apparitions) {
            a.tick(tick);
            if (!a.gone()) {
                allGone = false;
            }
        }
        return allGone;
    }

    /** Copies each viewer's reaction into the instance outcome. */
    protected void collectReactions() {
        for (Apparition a : apparitions) {
            for (Apparition.View v : a.views()) {
                setOutcome(v.id(), a.reactionOf(v.id()));
            }
        }
    }

    protected void vanish(Apparition a) {
        collectReactions();
        a.vanish();
    }

    protected void finishWithReactions() {
        collectReactions();
        for (Apparition a : apparitions) {
            a.vanish();
        }
        finish(Outcome.UNNOTICED);
    }

    @Override
    protected boolean owns(HimBody body) {
        for (Apparition a : apparitions) {
            if (a.body() == body) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void onBodyHit(HimBody body, Player attacker) {
        for (Apparition a : apparitions) {
            if (a.body() == body) {
                a.markAttacked(attacker.getUniqueId());
                setOutcome(attacker.getUniqueId(), Outcome.ATTACKED);
                // He does not stand there and take it: he is simply gone.
                vanish(a);
            }
        }
    }

    @Override
    protected void onParticipantLost(UUID player, LossReason reason) {
        for (Apparition a : apparitions) {
            a.forgetViewer(player);
        }
        super.onParticipantLost(player, reason);
    }

    @Override
    protected void cleanup() {
        for (Apparition a : apparitions) {
            a.vanish();
        }
    }
}
