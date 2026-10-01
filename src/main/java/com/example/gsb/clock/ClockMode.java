package com.example.gsb.clock;

/** Operating mode of {@link MonotonicClock}. */
public enum ClockMode {

    /** Wall clock behaves normally; logical time follows it. */
    NORMAL,

    /** A small rollback was detected; logical time is waiting for the wall clock to catch up. */
    COMPENSATING,

    /** A large rollback was detected; time requests fail until the wall clock recovers. */
    FAILED
}
