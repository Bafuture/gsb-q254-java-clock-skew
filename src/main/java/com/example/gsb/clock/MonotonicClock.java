package com.example.gsb.clock;

import java.util.Objects;

/**
 * 时钟回拨处理与单调时间组件。
 *
 * <p>在可注入的 {@link ClockSource}（墙上时钟）之上维护一个逻辑时钟，
 * 保证对外暴露的时间永不倒退：</p>
 * <ul>
 *   <li>墙上时钟正常前进时，逻辑时间跟随墙上时钟；</li>
 *   <li>检测到回拨时记录幅度与次数，并按当前偏移区分小幅抖动与大幅回拨；</li>
 *   <li>小幅回拨（偏移 &le; 阈值）先在有界预算内等待墙上时钟追上，
 *       预算耗尽则逐毫秒补偿推进逻辑时间，自愈期间不会产生重复时间戳；</li>
 *   <li>大幅回拨（偏移 &gt; 阈值）立即抛出 {@link ClockBackwardsException}，
 *       并在偏移降回阈值以内之前持续失败，绝不静默返回错误时间；</li>
 *   <li>墙上时钟重新追上逻辑时间后平滑恢复正常模式，并记录恢复点。</li>
 * </ul>
 *
 * <p>{@link #nextSequence()} 由单调时间派生严格递增且不重复的序号，
 * 同一毫秒内最多支持 2^{@value #SEQUENCE_BITS} 个序号，超出时逻辑时间向前进位。</p>
 *
 * <p>所有公共方法都是线程安全的。</p>
 */
public final class MonotonicClock {

    /**
     * 序号中毫秒内计数器占用的位数（每毫秒最多 4096 个序号）。
     */
    static final int SEQUENCE_BITS = 12;

    private static final long MAX_SEQUENCE_PER_MILLIS = (1L << SEQUENCE_BITS) - 1;

    private final ClockSource clock;
    private final Sleeper sleeper;
    private final long smallRollbackThresholdMillis;
    private final long maxWaitMillis;
    private final ClockStatistics statistics = new ClockStatistics();

    private boolean initialized;
    private long lastWall;
    private long lastLogical;
    private boolean rollbackEpisodeActive;
    private boolean compensating;

    private long lastSequenceTimestamp = -1L;
    private long sequenceCounter;

    /**
     * @param clock                        墙上时钟来源（可注入以模拟回拨）
     * @param sleeper                      自愈等待策略（可注入以避免真实睡眠）
     * @param smallRollbackThresholdMillis 小幅回拨容忍阈值（毫秒），当前偏移超过它即视为大幅回拨
     * @param maxWaitMillis                单次自愈等待墙上时钟追平的最大时长（毫秒），耗尽后转为补偿
     */
    public MonotonicClock(ClockSource clock, Sleeper sleeper,
                          long smallRollbackThresholdMillis, long maxWaitMillis) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        if (smallRollbackThresholdMillis < 0) {
            throw new IllegalArgumentException("smallRollbackThresholdMillis must be >= 0");
        }
        if (maxWaitMillis < 0) {
            throw new IllegalArgumentException("maxWaitMillis must be >= 0");
        }
        this.smallRollbackThresholdMillis = smallRollbackThresholdMillis;
        this.maxWaitMillis = maxWaitMillis;
    }

    /**
     * 使用系统墙上时钟与真实睡眠创建默认实例。
     */
    public static MonotonicClock system(long smallRollbackThresholdMillis, long maxWaitMillis) {
        return new MonotonicClock(new SystemClockSource(), new SystemSleeper(),
                smallRollbackThresholdMillis, maxWaitMillis);
    }

    /**
     * 返回单调时间（毫秒）。墙上时钟回拨时该值也绝不倒退。
     *
     * @throws ClockBackwardsException 当前偏移超过小幅回拨阈值（大幅回拨）时抛出
     */
    public synchronized long now() {
        long wall = clock.currentTimeMillis();
        if (!initialized) {
            initialized = true;
            lastWall = wall;
            lastLogical = wall;
            return lastLogical;
        }

        if (wall < lastWall) {
            statistics.recordRollback(lastWall - wall);
            rollbackEpisodeActive = true;
        }
        lastWall = wall;

        long offset = lastLogical - wall;
        if (offset < 0 || (offset == 0 && !rollbackEpisodeActive)) {
            finishRecoveryIfNeeded(wall);
            lastLogical = Math.max(lastLogical, wall);
            return lastLogical;
        }

        if (offset > smallRollbackThresholdMillis) {
            throw new ClockBackwardsException(offset, smallRollbackThresholdMillis, lastLogical, wall);
        }

        if (!rollbackEpisodeActive) {
            return lastLogical;
        }

        wall = waitForCatchUp(wall);
        if (wall > lastLogical) {
            finishRecoveryIfNeeded(wall);
            lastLogical = wall;
            return lastLogical;
        }

        compensating = true;
        lastLogical++;
        return lastLogical;
    }

    /**
     * 生成由单调时间派生的序号：高 52 位为单调毫秒时间，低 {@value #SEQUENCE_BITS}
     * 位为毫秒内计数器。结果严格递增且全局不重复。
     */
    public synchronized long nextSequence() {
        long timestamp = now();
        if (timestamp == lastSequenceTimestamp) {
            sequenceCounter++;
            if (sequenceCounter > MAX_SEQUENCE_PER_MILLIS) {
                lastLogical = timestamp + 1;
                timestamp = lastLogical;
                sequenceCounter = 0;
            }
        } else {
            sequenceCounter = 0;
        }
        lastSequenceTimestamp = timestamp;
        statistics.incrementSequences();
        return (timestamp << SEQUENCE_BITS) | sequenceCounter;
    }

    /**
     * 运行统计（回拨次数、最大回拨幅度、补偿等待总时长、序号总数、恢复点）。
     */
    public ClockStatistics statistics() {
        return statistics;
    }

    /**
     * 当前是否处于补偿模式（等待预算耗尽后以逻辑推进代替墙上时钟）。
     */
    public synchronized boolean isCompensating() {
        return compensating;
    }

    /**
     * 当前是否处于尚未恢复的回拨事件中。
     */
    public synchronized boolean isRollbackEpisodeActive() {
        return rollbackEpisodeActive;
    }

    private long waitForCatchUp(long wall) {
        long waited = 0;
        while (wall <= lastLogical && waited < maxWaitMillis) {
            long chunk = Math.min(lastLogical + 1 - wall, maxWaitMillis - waited);
            try {
                sleeper.sleep(chunk);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            waited += chunk;
            statistics.addWaitMillis(chunk);
            wall = clock.currentTimeMillis();
            if (wall < lastWall) {
                statistics.recordRollback(lastWall - wall);
                rollbackEpisodeActive = true;
            }
            lastWall = wall;
            long offset = lastLogical - wall;
            if (offset > smallRollbackThresholdMillis) {
                throw new ClockBackwardsException(offset, smallRollbackThresholdMillis, lastLogical, wall);
            }
        }
        return wall;
    }

    private void finishRecoveryIfNeeded(long wall) {
        if (rollbackEpisodeActive) {
            statistics.recordRecovery(new RecoveryPoint(wall, wall));
            rollbackEpisodeActive = false;
            compensating = false;
        }
    }
}
