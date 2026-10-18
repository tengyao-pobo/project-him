package dev.projecthim.him.event;

import dev.projecthim.him.director.EventSpec;

/**
 * A kind of horror event: its platform independent spec plus a factory for running instances.
 * Implementations are stateless; all state lives in the {@link EventInstance}.
 */
public interface HorrorEvent {

    EventSpec spec();

    /** Creates an instance for the context. Returning null means the event cannot run here. */
    EventInstance create(EventContext context);

    default String id() {
        return spec().id();
    }
}
