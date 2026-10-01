package com.example.gsb.clock;

/**
 * Injectable wait primitive used while compensating small clock rollbacks.
 * Extracting it lets tests run instantly by making the sleep a no-op or by
 * advancing a controllable time source instead.
 */
@FunctionalInterface
public interface Sleeper {

    void sleep(long millis) throws InterruptedException;

    /** Real waiting backed by {@link Thread#sleep(long)}. */
    Sleeper SYSTEM = Thread::sleep;

    /** No waiting: compensation is performed purely logically. */
    Sleeper NO_OP = millis -> {
    };
}
