package com.example.gsb.clock;

import java.util.Objects;

/**
 * Generates strictly increasing, never-repeating 64-bit ids derived from a
 * {@link MonotonicClock}.
 *
 * <p>Bit layout: the upper bits are the monotonic epoch-millisecond timestamp;
 * the low {@value #SEQUENCE_BITS} bits are a per-millisecond sequence
 * ({@code 0}..{@value #MAX_SEQUENCE}). Multiple requests within the same
 * millisecond get consecutive sequence numbers; if the per-millisecond budget
 * is exhausted the generator waits for logical time to advance. Because the
 * underlying timestamp never moves backwards (even across wall-clock
 * rollbacks), generated ids are globally strictly increasing.
 */
public final class SequenceGenerator {

    public static final int SEQUENCE_BITS = 12;
    public static final long SEQUENCE_MASK = (1L << SEQUENCE_BITS) - 1L;
    public static final long MAX_SEQUENCE = SEQUENCE_MASK;

    private final MonotonicClock clock;

    private long lastTimestamp = Long.MIN_VALUE;
    private long sequence;

    public SequenceGenerator(MonotonicClock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public synchronized long nextId() {
        long timestamp = clock.currentTimeMillis();

        if (timestamp < lastTimestamp) {
            throw new IllegalStateException(
                    "Monotonic clock returned an earlier timestamp: " + timestamp + " < " + lastTimestamp);
        }

        if (timestamp == lastTimestamp) {
            if (sequence == MAX_SEQUENCE) {
                timestamp = waitNextMillis(lastTimestamp);
                sequence = 0;
            } else {
                sequence++;
            }
        } else {
            sequence = 0;
        }

        lastTimestamp = timestamp;
        clock.onSequenceGenerated();
        return (timestamp << SEQUENCE_BITS) | sequence;
    }

    private long waitNextMillis(long current) {
        long timestamp = clock.currentTimeMillis();
        while (timestamp <= current) {
            Thread.onSpinWait();
            timestamp = clock.currentTimeMillis();
        }
        return timestamp;
    }

    /** Timestamp component (epoch millis) of an id produced by this generator. */
    public static long extractTimestamp(long id) {
        return id >>> SEQUENCE_BITS;
    }

    /** Per-millisecond sequence component of an id produced by this generator. */
    public static long extractSequence(long id) {
        return id & SEQUENCE_MASK;
    }
}
