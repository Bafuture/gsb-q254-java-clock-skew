package com.example.gsb.clock;

/**
 * Immutable statistics snapshot of a {@link MonotonicClock}.
 *
 * @param rollbackCount               total number of detected rollbacks (small and large)
 * @param smallRollbackCount          number of small (self-healed) rollbacks
 * @param largeRollbackCount          number of large (fatal) rollbacks
 * @param maxRollbackMillis           largest single rollback amplitude in milliseconds
 * @param totalCompensationWaitMillis total logical wait spent compensating small rollbacks
 * @param generatedSequenceCount      total number of sequence ids derived from this clock
 * @param mode                        current operating mode
 */
public record ClockStats(long rollbackCount,
                         long smallRollbackCount,
                         long largeRollbackCount,
                         long maxRollbackMillis,
                         long totalCompensationWaitMillis,
                         long generatedSequenceCount,
                         ClockMode mode) {
}
