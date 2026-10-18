package dev.projecthim.him.util;

/** Wall clock abstraction so time dependent logic can be unit tested. */
@FunctionalInterface
public interface Clock {

    Clock SYSTEM = System::currentTimeMillis;

    long now();
}
