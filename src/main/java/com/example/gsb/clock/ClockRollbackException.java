package com.example.gsb.clock;

/**
 * Thrown immediately when the wall clock rolls back by more than the configured
 * tolerance. The component never silently returns a potentially duplicated or
 * wrong timestamp in that situation.
 */
public class ClockRollbackException extends RuntimeException {

    private final long currentOffsetMillis;
    private final long thresholdMillis;
    private final long recoveryPointMillis;
    private final long currentWallClockMillis;

    public ClockRollbackException(long currentOffsetMillis,
                                  long thresholdMillis,
                                  long recoveryPointMillis,
                                  long currentWallClockMillis) {
        super("Wall clock rollback exceeds tolerance: current offset " + currentOffsetMillis
                + " ms behind recovery point (threshold " + thresholdMillis + " ms); wallClock="
                + currentWallClockMillis + ", recoveryPoint=" + recoveryPointMillis
                + ". Refusing to emit a potentially duplicated timestamp.");
        this.currentOffsetMillis = currentOffsetMillis;
        this.thresholdMillis = thresholdMillis;
        this.recoveryPointMillis = recoveryPointMillis;
        this.currentWallClockMillis = currentWallClockMillis;
    }

    /** How far the wall clock currently lags behind the pre-rollback recovery point. */
    public long getCurrentOffsetMillis() {
        return currentOffsetMillis;
    }

    public long getThresholdMillis() {
        return thresholdMillis;
    }

    public long getRecoveryPointMillis() {
        return recoveryPointMillis;
    }

    public long getCurrentWallClockMillis() {
        return currentWallClockMillis;
    }
}
