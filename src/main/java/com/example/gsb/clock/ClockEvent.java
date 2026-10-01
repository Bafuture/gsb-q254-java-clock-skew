package com.example.gsb.clock;

/**
 * A notable moment in the lifecycle of a {@link MonotonicClock}.
 *
 * @param type              kind of event
 * @param wallClockMillis   wall clock reading when the event was detected
 * @param rollbackMillis    rollback amplitude in milliseconds (0 for recovery)
 * @param logicalTimeMillis logical time handed out most recently at that moment
 */
public record ClockEvent(Type type, long wallClockMillis, long rollbackMillis, long logicalTimeMillis) {

    public enum Type {
        SMALL_ROLLBACK,
        LARGE_ROLLBACK,
        RECOVERED
    }
}
