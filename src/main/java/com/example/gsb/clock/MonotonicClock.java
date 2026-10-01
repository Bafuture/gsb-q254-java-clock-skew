package com.example.gsb.clock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * Monotonic time source built on top of an injectable wall clock.
 *
 * <p>Guarantees that the logical time returned by {@link #currentTimeMillis()}
 * never moves backwards, even when the underlying wall clock does:
 * <ul>
 *   <li>rollback at or below the threshold is treated as small jitter and
 *       self-healed by waiting for the wall clock to catch up (or by advancing
 *       logical time if waiting is a no-op). Every compensated call still
 *       produces a value strictly greater than the previous one;</li>
 *   <li>rollback above the threshold fails fast with
 *       {@link ClockRollbackException} stating the current offset, until the
 *       wall clock regains the pre-rollback recovery point;</li>
 *   <li>once the wall clock has recovered, the component smoothly switches
 *       back to {@link ClockMode#NORMAL}, holding logical time (never jumping
 *       it backwards) and recording the recovery point.</li>
 * </ul>
 *
 * <p>All methods are synchronized: logical time is a single ordered stream.
 */
public final class MonotonicClock {

    /** Rollbacks strictly greater than this many milliseconds are considered large. */
    public static final long DEFAULT_SMALL_ROLLBACK_THRESHOLD_MILLIS = 100L;

    private final TimeSource timeSource;
    private final Sleeper sleeper;
    private final long smallRollbackThresholdMillis;

    private ClockMode mode = ClockMode.NORMAL;

    /** Last raw wall clock reading, used to detect rollbacks. */
    private long lastWallClock = Long.MIN_VALUE;

    /** Last logical time handed out; never decreases. */
    private long lastLogical = Long.MIN_VALUE;

    /** Wall clock level that must be regained to leave a degraded mode. */
    private long recoveryPoint = Long.MIN_VALUE;

    private long rollbackCount;
    private long smallRollbackCount;
    private long largeRollbackCount;
    private long maxRollbackMillis;
    private long totalCompensationWaitMillis;
    private long generatedSequenceCount;
    private long lastRecoveryPointMillis = Long.MIN_VALUE;

    private final List<ClockEvent> events = new ArrayList<>();

    public MonotonicClock(TimeSource timeSource, Sleeper sleeper, long smallRollbackThresholdMillis) {
        this.timeSource = Objects.requireNonNull(timeSource, "timeSource");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        if (smallRollbackThresholdMillis < 0) {
            throw new IllegalArgumentException("smallRollbackThresholdMillis must be non-negative");
        }
        this.smallRollbackThresholdMillis = smallRollbackThresholdMillis;
    }

    public MonotonicClock(TimeSource timeSource, Sleeper sleeper) {
        this(timeSource, sleeper, DEFAULT_SMALL_ROLLBACK_THRESHOLD_MILLIS);
    }

    public MonotonicClock(TimeSource timeSource) {
        this(timeSource, Sleeper.SYSTEM, DEFAULT_SMALL_ROLLBACK_THRESHOLD_MILLIS);
    }

    /**
     * Returns the current monotonic epoch-millisecond time.
     *
     * @throws ClockRollbackException if a large rollback has not been recovered yet
     */
    public synchronized long currentTimeMillis() {
        long raw = timeSource.currentTimeMillis();

        if (lastWallClock != Long.MIN_VALUE && raw < lastWallClock) {
            onRollback(lastWallClock - raw, raw);
        }
        lastWallClock = raw;

        if (mode == ClockMode.FAILED) {
            if (raw >= recoveryPoint) {
                recover(raw);
            } else {
                throw new ClockRollbackException(
                        recoveryPoint - raw,
                        smallRollbackThresholdMillis,
                        recoveryPoint,
                        raw);
            }
        }

        if (mode == ClockMode.COMPENSATING) {
            if (raw >= recoveryPoint) {
                recover(raw);
            } else {
                return compensate(raw);
            }
        }

        // NORMAL, including a freshly recovered call: never move backwards even
        // if compensation previously pushed logical time ahead of the wall clock.
        lastLogical = Math.max(raw, lastLogical);
        return lastLogical;
    }

    public synchronized ClockMode mode() {
        return mode;
    }

    public synchronized ClockStats stats() {
        return new ClockStats(
                rollbackCount,
                smallRollbackCount,
                largeRollbackCount,
                maxRollbackMillis,
                totalCompensationWaitMillis,
                generatedSequenceCount,
                mode);
    }

    /** Immutable copy of all rollback/recovery events, in detection order. */
    public synchronized List<ClockEvent> events() {
        return Collections.unmodifiableList(new ArrayList<>(events));
    }

    /** Wall clock reading at which the most recent recovery happened. */
    public synchronized OptionalLong lastRecoveryPoint() {
        return lastRecoveryPointMillis == Long.MIN_VALUE
                ? OptionalLong.empty()
                : OptionalLong.of(lastRecoveryPointMillis);
    }

    /** Called by {@link SequenceGenerator}; part of the component's statistics. */
    synchronized void onSequenceGenerated() {
        generatedSequenceCount++;
    }

    private void onRollback(long amplitude, long raw) {
        rollbackCount++;
        maxRollbackMillis = Math.max(maxRollbackMillis, amplitude);
        recoveryPoint = Math.max(recoveryPoint, lastWallClock);

        if (amplitude > smallRollbackThresholdMillis) {
            largeRollbackCount++;
            mode = ClockMode.FAILED;
            events.add(new ClockEvent(ClockEvent.Type.LARGE_ROLLBACK, raw, amplitude, lastLogical));
        } else {
            smallRollbackCount++;
            if (mode != ClockMode.FAILED) {
                mode = ClockMode.COMPENSATING;
            }
            events.add(new ClockEvent(ClockEvent.Type.SMALL_ROLLBACK, raw, amplitude, lastLogical));
        }
    }

    /**
     * Small-jitter self-healing. Wait until the wall clock reaches one
     * millisecond past the last logical time, then return at least that value,
     * so the compensated timestamp is strictly new (never duplicated). If the
     * wall clock recovers while sleeping, normal mode is resumed.
     */
    private long compensate(long raw) {
        long target = lastLogical + 1;
        long waitMillis = target - raw;
        if (waitMillis > 0) {
            try {
                sleeper.sleep(waitMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while compensating clock rollback", e);
            }
            totalCompensationWaitMillis += waitMillis;
            raw = timeSource.currentTimeMillis();
            lastWallClock = raw;
            if (raw >= recoveryPoint) {
                recover(raw);
            }
        }
        lastLogical = Math.max(raw, target);
        return lastLogical;
    }

    private void recover(long raw) {
        mode = ClockMode.NORMAL;
        recoveryPoint = Long.MIN_VALUE;
        lastRecoveryPointMillis = raw;
        events.add(new ClockEvent(ClockEvent.Type.RECOVERED, raw, 0L, lastLogical));
    }
}
