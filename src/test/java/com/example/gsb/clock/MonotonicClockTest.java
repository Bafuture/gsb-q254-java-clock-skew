package com.example.gsb.clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class MonotonicClockTest {

    private static final long THRESHOLD = 100L;

    private ManualTimeSource wall;
    private RecordingSleeper sleeper;
    private MonotonicClock clock;

    private void newClock(RecordingSleeper recordingSleeper) {
        wall = new ManualTimeSource(1_000L);
        sleeper = recordingSleeper;
        clock = new MonotonicClock(wall, sleeper, THRESHOLD);
    }

    @Test
    void followsWallClockInNormalMode() {
        newClock(new RecordingSleeper());

        assertThat(clock.currentTimeMillis()).isEqualTo(1_000L);
        wall.advance(5);
        assertThat(clock.currentTimeMillis()).isEqualTo(1_005L);
        wall.advance(1);
        assertThat(clock.currentTimeMillis()).isEqualTo(1_006L);

        assertThat(clock.mode()).isEqualTo(ClockMode.NORMAL);
        assertThat(clock.events()).isEmpty();
        assertThat(clock.stats().rollbackCount()).isZero();
    }

    @Test
    void logicalTimeIsNonDecreasingWhenWallClockStandsStill() {
        newClock(new RecordingSleeper());

        assertThat(clock.currentTimeMillis()).isEqualTo(1_000L);
        assertThat(clock.currentTimeMillis()).isEqualTo(1_000L);
    }

    @Test
    void smallRollbackSelfHealsWithoutDuplicatingTimestamps() {
        newClock(new RecordingSleeper());
        long first = clock.currentTimeMillis();

        wall.set(995L); // 5 ms jitter

        long compensated1 = clock.currentTimeMillis();
        long compensated2 = clock.currentTimeMillis();
        long compensated3 = clock.currentTimeMillis();

        assertThat(compensated1).isGreaterThan(first);
        assertThat(compensated2).isGreaterThan(compensated1);
        assertThat(compensated3).isGreaterThan(compensated2);
        assertThat(clock.mode()).isEqualTo(ClockMode.COMPENSATING);

        List<ClockEvent> events = clock.events();
        assertThat(events).extracting(ClockEvent::type)
                .containsExactly(ClockEvent.Type.SMALL_ROLLBACK);
        assertThat(events.get(0).rollbackMillis()).isEqualTo(5L);
        assertThat(events.get(0).wallClockMillis()).isEqualTo(995L);
    }

    @Test
    void smallRollbackWaitsForWallClockAndRecoversSmoothly() {
        ManualTimeSource advancingWall = new ManualTimeSource(1_000L);
        RecordingSleeper advancingSleeper = new RecordingSleeper(advancingWall);
        MonotonicClock advancingClock = new MonotonicClock(advancingWall, advancingSleeper, THRESHOLD);

        long before = advancingClock.currentTimeMillis();
        advancingWall.set(995L);
        long recovered = advancingClock.currentTimeMillis();

        // Waited exactly until the wall clock reached one ms past the pre-rollback value.
        assertThat(advancingSleeper.sleeps()).containsExactly(6L);
        assertThat(recovered).isEqualTo(1_001L).isGreaterThan(before);
        assertThat(advancingClock.mode()).isEqualTo(ClockMode.NORMAL);
        assertThat(advancingClock.lastRecoveryPoint()).isPresent().hasValue(1_001L);
        assertThat(advancingClock.events()).extracting(ClockEvent::type)
                .containsExactly(ClockEvent.Type.SMALL_ROLLBACK, ClockEvent.Type.RECOVERED);

        advancingWall.advance(10);
        assertThat(advancingClock.currentTimeMillis()).isEqualTo(1_011L);
        assertThat(advancingClock.stats().totalCompensationWaitMillis()).isEqualTo(6L);
    }

    @Test
    void repeatedSmallJittersNeverMoveLogicalTimeBackwards() {
        newClock(new RecordingSleeper());
        long previous = clock.currentTimeMillis();

        wall.set(990L);
        previous = assertIncreasing(previous); // 10 ms jitter
        wall.set(980L);
        previous = assertIncreasing(previous); // another 10 ms jitter
        wall.set(970L);
        previous = assertIncreasing(previous); // and another

        assertThat(clock.mode()).isEqualTo(ClockMode.COMPENSATING);
        ClockStats stats = clock.stats();
        assertThat(stats.rollbackCount()).isEqualTo(3);
        assertThat(stats.smallRollbackCount()).isEqualTo(3);
        assertThat(stats.largeRollbackCount()).isZero();
        assertThat(stats.maxRollbackMillis()).isEqualTo(10L);
        // waits: (1001-990) + (1002-980) + (1003-970)
        assertThat(stats.totalCompensationWaitMillis()).isEqualTo(66L);
    }

    @Test
    void largeRollbackFailsImmediatelyAndReportsCurrentOffset() {
        wall = new ManualTimeSource(10_000L);
        sleeper = new RecordingSleeper();
        clock = new MonotonicClock(wall, sleeper, THRESHOLD);
        assertThat(clock.currentTimeMillis()).isEqualTo(10_000L);

        wall.set(4_000L); // jump back 6000 ms, way above the 100 ms threshold
        assertThatThrownBy(clock::currentTimeMillis)
                .isInstanceOf(ClockRollbackException.class)
                .hasMessageContaining("6000")
                .satisfies(ex -> {
                    ClockRollbackException e = (ClockRollbackException) ex;
                    assertThat(e.getCurrentOffsetMillis()).isEqualTo(6_000L);
                    assertThat(e.getRecoveryPointMillis()).isEqualTo(10_000L);
                    assertThat(e.getCurrentWallClockMillis()).isEqualTo(4_000L);
                    assertThat(e.getThresholdMillis()).isEqualTo(THRESHOLD);
                });

        // Keeps failing (never silently returns a bad time); offset shrinks as wall clock climbs.
        wall.set(8_000L);
        assertThatThrownBy(clock::currentTimeMillis)
                .isInstanceOf(ClockRollbackException.class)
                .satisfies(ex -> assertThat(((ClockRollbackException) ex).getCurrentOffsetMillis())
                        .isEqualTo(2_000L));

        // A further rollback while failed is still recorded.
        wall.set(7_500L);
        assertThatThrownBy(clock::currentTimeMillis).isInstanceOf(ClockRollbackException.class);

        ClockStats stats = clock.stats();
        assertThat(stats.mode()).isEqualTo(ClockMode.FAILED);
        assertThat(stats.rollbackCount()).isEqualTo(2);
        assertThat(stats.largeRollbackCount()).isEqualTo(2);
        assertThat(stats.maxRollbackMillis()).isEqualTo(6_000L);
    }

    @Test
    void recoversToNormalModeAfterLargeRollbackIsHealed() {
        wall = new ManualTimeSource(10_000L);
        sleeper = new RecordingSleeper();
        clock = new MonotonicClock(wall, sleeper, THRESHOLD);
        assertThat(clock.currentTimeMillis()).isEqualTo(10_000L);
        wall.set(4_000L);
        assertThatThrownBy(clock::currentTimeMillis).isInstanceOf(ClockRollbackException.class);

        // Wall clock restored to the pre-rollback recovery point: calls work again.
        wall.set(10_000L);
        long atRecovery = clock.currentTimeMillis();
        assertThat(atRecovery).isEqualTo(10_000L);
        assertThat(clock.mode()).isEqualTo(ClockMode.NORMAL);
        assertThat(clock.lastRecoveryPoint()).isPresent().hasValue(10_000L);
        assertThat(clock.events()).extracting(ClockEvent::type)
                .contains(ClockEvent.Type.LARGE_ROLLBACK, ClockEvent.Type.RECOVERED);

        wall.advance(9);
        assertThat(clock.currentTimeMillis()).isEqualTo(10_009L);
    }

    @Test
    void rollbackDuringCompensationEscalatesToFailure() {
        newClock(new RecordingSleeper());
        assertThat(clock.currentTimeMillis()).isEqualTo(1_000L);

        wall.set(995L);
        assertThat(clock.currentTimeMillis()).isEqualTo(1_001L);
        assertThat(clock.mode()).isEqualTo(ClockMode.COMPENSATING);

        wall.set(500L); // second, much larger jitter
        assertThatThrownBy(clock::currentTimeMillis)
                .isInstanceOf(ClockRollbackException.class)
                .satisfies(ex -> assertThat(((ClockRollbackException) ex).getCurrentOffsetMillis())
                        .isEqualTo(500L));

        ClockStats stats = clock.stats();
        assertThat(stats.mode()).isEqualTo(ClockMode.FAILED);
        assertThat(stats.smallRollbackCount()).isEqualTo(1);
        assertThat(stats.largeRollbackCount()).isEqualTo(1);
        assertThat(stats.rollbackCount()).isEqualTo(2);
        assertThat(stats.maxRollbackMillis()).isEqualTo(495L);
    }

    @Test
    void recoveryIsSmoothLogicalTimeNeverJumpsBackwards() {
        newClock(new RecordingSleeper());
        long t0 = clock.currentTimeMillis(); // 1000

        wall.set(995L);
        long t1 = clock.currentTimeMillis(); // 1001 (compensated)
        wall.set(996L);
        long t2 = clock.currentTimeMillis(); // 1002 (compensated)

        wall.set(1_000L); // wall clock back at the recovery point
        long t3 = clock.currentTimeMillis();
        wall.set(1_005L);
        long t4 = clock.currentTimeMillis();

        assertThat(List.of(t0, t1, t2, t3, t4)).isSorted(); // smooth, non-decreasing
        assertThat(t3).isEqualTo(1_002L); // holds instead of jumping back to 1000
        assertThat(t4).isEqualTo(1_005L);
        assertThat(clock.mode()).isEqualTo(ClockMode.NORMAL);
        assertThat(clock.events()).extracting(ClockEvent::type)
                .endsWith(ClockEvent.Type.RECOVERED);
    }

    @Test
    void rollbackExactlyAtThresholdIsSmallRollback() {
        newClock(new RecordingSleeper());
        clock.currentTimeMillis();
        wall.set(900L); // exactly 100 ms back
        assertThat(clock.currentTimeMillis()).isEqualTo(1_001L);
        assertThat(clock.mode()).isEqualTo(ClockMode.COMPENSATING);
        assertThat(clock.stats().smallRollbackCount()).isEqualTo(1);
        assertThat(clock.stats().largeRollbackCount()).isZero();
    }

    @Test
    void rollbackBeyondThresholdIsLargeRollback() {
        newClock(new RecordingSleeper());
        clock.currentTimeMillis();
        wall.set(899L); // 101 ms back
        assertThatThrownBy(clock::currentTimeMillis).isInstanceOf(ClockRollbackException.class);
        assertThat(clock.stats().largeRollbackCount()).isEqualTo(1);
    }

    @Test
    void statsAggregateEverything() {
        newClock(new RecordingSleeper());
        clock.currentTimeMillis();          // 1000
        wall.set(995L);
        clock.currentTimeMillis();          // compensated -> 1001, waits 6
        wall.set(996L);
        clock.currentTimeMillis();          // compensated -> 1002, waits 6
        wall.set(1_000L);
        clock.currentTimeMillis();          // recovered -> holds 1002

        ClockStats stats = clock.stats();
        assertThat(stats.rollbackCount()).isEqualTo(1);
        assertThat(stats.smallRollbackCount()).isEqualTo(1);
        assertThat(stats.largeRollbackCount()).isZero();
        assertThat(stats.maxRollbackMillis()).isEqualTo(5L);
        assertThat(stats.totalCompensationWaitMillis()).isEqualTo(12L);
        assertThat(stats.generatedSequenceCount()).isZero();
        assertThat(stats.mode()).isEqualTo(ClockMode.NORMAL);
    }

    @Test
    void rejectsNegativeThreshold() {
        assertThatThrownBy(() -> new MonotonicClock(TimeSource.system(), Sleeper.NO_OP, -1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private long assertIncreasing(long previous) {
        long next = clock.currentTimeMillis();
        assertThat(next).isGreaterThan(previous);
        return next;
    }
}
