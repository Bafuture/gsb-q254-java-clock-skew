package com.example.gsb.clock;

/**
 * Injectable wall-clock time source, returning epoch milliseconds.
 * Tests supply a controllable implementation to simulate clock rollback.
 */
@FunctionalInterface
public interface TimeSource {

    long currentTimeMillis();

    /** Wall clock backed by {@link System#currentTimeMillis()}. */
    static TimeSource system() {
        return System::currentTimeMillis;
    }
}
