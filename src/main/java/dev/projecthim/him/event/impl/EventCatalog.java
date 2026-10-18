package dev.projecthim.him.event.impl;

import dev.projecthim.him.event.HorrorEvent;
import java.util.List;

/** Every built-in event. New events only need to be added here. */
public final class EventCatalog {

    private EventCatalog() {
    }

    public static List<HorrorEvent> create() {
        return List.of(
                // Presence: heard, felt, never seen.
                new FootstepsBehindEvent(),
                new CaveMiningEvent(),
                new MimicEchoEvent(),
                new DoorKnockEvent(),
                new DreadEvent(),
                new FalseJoinEvent(),
                // Observe: he lets himself be seen.
                new DistantFigureEvent(),
                new CoverPeekEvent(),
                new OverShoulderEvent(),
                new WindowWatcherEvent(),
                new BedWatcherEvent(),
                new LandmarkReturnEvent(),
                new ReturnGreetingEvent(),
                new LightningRevealEvent(),
                // Stalk: he follows.
                new StalkerEvent(),
                new BehindCompanionEvent(),
                // Interfere: he touches the world.
                new TorchSnuffEvent(),
                new WallCrackEvent(),
                new FakeSignEvent(),
                new ShrineEvent(),
                new LeaflessTreeEvent(),
                new TunnelEvent(),
                new DoorLeftOpenEvent(),
                // Hunt.
                new HuntEvent(),
                // Administrator only.
                new SummonEvent());
    }
}
