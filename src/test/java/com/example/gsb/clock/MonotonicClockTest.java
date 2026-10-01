package com.example.gsb.clock;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MonotonicClockTest {

    private static final long THRESHOLD = 50L;

    @Test
    void monotonicTimeFollowsWallClockAndNeverMovesBackwards() {
        MutableClock wall = new MutableClock(1000L);
        FakeSleeper sleeper = FakeSleeper.advancing(wall);
        MonotonicClock clock = new MonotonicClock(wall, sleeper, THRESHOLD, 1000L);

        assertThat(clock.now()).isEqualTo(1000L);
        wall.advance(5);
        assertThat(clock.now()).isEqualTo(1005L);
        assertThat(clock.now()).isEqualTo(1005L);
        wall.advance(100);
        assertThat(clock.now()).isEqualTo(1105L);

        wall.set(1103L);
        assertThat(clock.now())
                .as("小幅抖动后逻辑时间不得倒退")
                .isGreaterThan(1105L)
                .isEqualTo(1106L);
        wall.advance(20);
        assertThat(clock.now()).isEqualTo(1126L);

        assertThat(clock.statistics().rollbackCount()).isEqualTo(1L);
        assertThat(clock.statistics().maxRollbackMillis()).isEqualTo(2L);
    }

    @Test
    void smallRollbackHealsByWaitingForWallClockToCatchUp() {
        MutableClock wall = new MutableClock(1000L);
        FakeSleeper sleeper = FakeSleeper.advancing(wall);
        MonotonicClock clock = new MonotonicClock(wall, sleeper, THRESHOLD, 100L);

        assertThat(clock.now()).isEqualTo(1000L);
        wall.set(990L);

        long healed = clock.now();

        assertThat(healed).as("等待追平后不重复、不倒退").isEqualTo(1001L);
        assertThat(clock.isCompensating()).isFalse();
        assertThat(clock.isRollbackEpisodeActive()).isFalse();

        ClockStatistics stats = clock.statistics();
        assertThat(stats.rollbackCount()).isEqualTo(1L);
        assertThat(stats.maxRollbackMillis()).isEqualTo(10L);
        assertThat(stats.totalWaitMillis()).isEqualTo(11L);
        assertThat(sleeper.totalSlept()).isEqualTo(11L);
        assertThat(stats.recoveryCount()).isEqualTo(1L);
        assertThat(stats.lastRecoveryPoint())
                .isEqualTo(new RecoveryPoint(1001L, 1001L));

        wall.advance(10);
        assertThat(clock.now()).isEqualTo(1011L);
    }

    @Test
    void smallRollbackCompensatesWithStrictlyIncreasingLogicalTimeWhenWaitBudgetIsExhausted() {
        MutableClock wall = new MutableClock(1000L);
        FakeSleeper sleeper = FakeSleeper.stuck();
        MonotonicClock clock = new MonotonicClock(wall, sleeper, THRESHOLD, 4L);

        assertThat(clock.now()).isEqualTo(1000L);
        wall.set(990L);

        long first = clock.now();
        long second = clock.now();

        assertThat(first).as("等待预算耗尽后逻辑时间逐毫秒补偿").isEqualTo(1001L);
        assertThat(second).isEqualTo(1002L);
        assertThat(second).isGreaterThan(first);
        assertThat(clock.isCompensating()).isTrue();
        assertThat(clock.isRollbackEpisodeActive()).isTrue();

        ClockStatistics stats = clock.statistics();
        assertThat(stats.rollbackCount()).as("同一回拨事件不重复计数").isEqualTo(1L);
        assertThat(stats.maxRollbackMillis()).isEqualTo(10L);
        assertThat(stats.totalWaitMillis()).isEqualTo(8L);
        assertThat(stats.recoveryCount()).isZero();
    }

    @Test
    void largeRollbackFailsFastWithCurrentOffsetAndKeepsFailingUntilOffsetShrinks() {
        MutableClock wall = new MutableClock(10_000L);
        FakeSleeper sleeper = FakeSleeper.stuck();
        MonotonicClock clock = new MonotonicClock(wall, sleeper, THRESHOLD, 100L);

        assertThat(clock.now()).isEqualTo(10_000L);
        wall.set(9800L);

        assertThatThrownBy(clock::now)
                .isInstanceOfSatisfying(ClockBackwardsException.class, ex -> {
                    assertThat(ex.currentOffsetMillis()).isEqualTo(200L);
                    assertThat(ex.thresholdMillis()).isEqualTo(THRESHOLD);
                    assertThat(ex.logicalMillis()).isEqualTo(10_000L);
                    assertThat(ex.wallMillis()).isEqualTo(9800L);
                    assertThat(ex.getMessage()).contains("200", "50");
                })
                .as("大幅回拨必须立即抛出，不得静默返回错误时间");

        assertThatThrownBy(clock::now)
                .as("偏移未降回阈值以内前应持续失败")
                .isInstanceOf(ClockBackwardsException.class);

        wall.set(9970L);
        long afterShrink = clock.now();
        assertThat(afterShrink).as("偏移回到小幅范围后通过补偿继续，且不倒退").isEqualTo(10_001L);

        wall.set(20_000L);
        assertThat(clock.now()).as("墙上时钟追上后平滑恢复").isEqualTo(20_000L);
        assertThat(clock.isRollbackEpisodeActive()).isFalse();
        assertThat(clock.statistics().rollbackCount()).isEqualTo(1L);
        assertThat(clock.statistics().maxRollbackMillis()).isEqualTo(200L);
        assertThat(clock.statistics().recoveryCount()).isEqualTo(1L);
        assertThat(clock.statistics().lastRecoveryPoint())
                .isEqualTo(new RecoveryPoint(20_000L, 20_000L));
    }

    @Test
    void sequencesAreUniqueAndStrictlyIncreasingEvenWithinTheSameMillisecond() {
        MutableClock wall = new MutableClock(5000L);
        FakeSleeper sleeper = FakeSleeper.stuck();
        MonotonicClock clock = new MonotonicClock(wall, sleeper, THRESHOLD, 0L);

        int count = 10_000;
        long[] sequences = new long[count];
        for (int i = 0; i < count; i++) {
            sequences[i] = clock.nextSequence();
        }

        assertThat(sequences[0]).isEqualTo(5000L << 12);
        Set<Long> unique = new HashSet<>();
        for (int i = 0; i < count; i++) {
            unique.add(sequences[i]);
            if (i > 0) {
                assertThat(sequences[i]).as("序号必须严格递增").isGreaterThan(sequences[i - 1]);
            }
        }
        assertThat(unique).hasSize(count);
        assertThat(clock.statistics().totalSequencesGenerated()).isEqualTo(count);
    }

    @Test
    void recoveryAfterCompensationIsSmoothAndRecorded() {
        MutableClock wall = new MutableClock(1000L);
        FakeSleeper sleeper = FakeSleeper.stuck();
        MonotonicClock clock = new MonotonicClock(wall, sleeper, THRESHOLD, 0L);

        assertThat(clock.now()).isEqualTo(1000L);
        wall.set(995L);
        assertThat(clock.now()).isEqualTo(1001L);
        assertThat(clock.now()).isEqualTo(1002L);

        wall.set(1010L);
        long recovered = clock.now();

        assertThat(recovered)
                .as("恢复时逻辑时间平滑切换回墙上时钟，不产生跳变倒退")
                .isEqualTo(1010L)
                .isGreaterThan(1002L);
        assertThat(clock.isCompensating()).isFalse();
        assertThat(clock.isRollbackEpisodeActive()).isFalse();
        assertThat(clock.statistics().recoveryCount()).isEqualTo(1L);
        assertThat(clock.statistics().lastRecoveryPoint())
                .isEqualTo(new RecoveryPoint(1010L, 1010L));

        wall.set(1012L);
        assertThat(clock.now()).isEqualTo(1012L);
    }

    @Test
    void statisticsTrackRollbacksMaximumAmplitudeWaitsRecoveriesAndSequences() {
        MutableClock wall = new MutableClock(1000L);
        FakeSleeper sleeper = FakeSleeper.advancing(wall);
        MonotonicClock clock = new MonotonicClock(wall, sleeper, THRESHOLD, 1000L);

        clock.now();
        wall.set(980L);
        clock.now();
        wall.set(990L);
        clock.now();
        for (int i = 0; i < 5; i++) {
            clock.nextSequence();
        }

        ClockStatistics stats = clock.statistics();
        assertThat(stats.rollbackCount()).isEqualTo(2L);
        assertThat(stats.maxRollbackMillis()).isEqualTo(20L);
        assertThat(stats.totalWaitMillis()).isEqualTo(21L + 12L);
        assertThat(stats.recoveryCount()).isEqualTo(2L);
        assertThat(stats.recoveryPoints()).hasSize(2);
        assertThat(stats.totalSequencesGenerated()).isEqualTo(5L);
    }
}
