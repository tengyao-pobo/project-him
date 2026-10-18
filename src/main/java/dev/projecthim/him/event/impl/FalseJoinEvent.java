package dev.projecthim.him.event.impl;

import dev.projecthim.him.director.EncounterMode;
import dev.projecthim.him.director.EventSpec;
import dev.projecthim.him.director.Intent;
import dev.projecthim.him.director.Stage;
import dev.projecthim.him.event.EventContext;
import dev.projecthim.him.event.EventInstance;
import dev.projecthim.him.event.HorrorEvent;
import dev.projecthim.him.memory.Outcome;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * "Herobrine joined the game", rendered by the client exactly like a real join message, shown to one
 * player only. Their friends saw nothing. Sometimes, a minute or two later, he leaves again.
 */
final class FalseJoinEvent implements HorrorEvent {

    private final EventSpec spec = EventSpec.builder("false_join", Intent.PRESENCE)
            .stage(Stage.WATCHED)
            .intensity(0.3)
            .cooldownMinutes(360)
            .weight(0.6)
            .modes(EncounterMode.PRIVATE)
            .build();

    @Override
    public EventSpec spec() {
        return spec;
    }

    @Override
    public EventInstance create(EventContext ctx) {
        return ctx.him().config().multiplayer().fakeChat() ? new Instance(ctx) : null;
    }

    private static final class Instance extends EventInstance {
        Instance(EventContext ctx) {
            super(ctx);
        }

        @Override
        protected boolean start() {
            Player p = ctx.target();
            String name = ctx.him().config().messages().fakeJoinName();
            p.sendMessage(Component.translatable("multiplayer.player.joined", NamedTextColor.YELLOW, Component.text(name)));
            if (ctx.him().random().nextDouble() < 0.5) {
                long delay = 20L * (60 + ctx.him().random().nextInt(70));
                Bukkit.getScheduler().runTaskLater(ctx.him().plugin(), () -> {
                    if (p.isOnline()) {
                        p.sendMessage(Component.translatable("multiplayer.player.left", NamedTextColor.YELLOW,
                                Component.text(name)));
                    }
                }, delay);
            }
            return true;
        }

        @Override
        protected void tick(long tick) {
            finish(Outcome.UNNOTICED);
        }

        @Override
        protected void cleanup() {
            // A chat message cannot be undone.
        }
    }
}
