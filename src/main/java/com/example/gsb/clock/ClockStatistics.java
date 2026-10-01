package com.example.gsb.clock;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 单调时钟的运行统计：回拨次数、最大回拨幅度、补偿等待总时长、
 * 生成的序号总数以及恢复点记录。
 *
 * <p>所有写入都发生在 {@link MonotonicClock} 的内部监视器下，
 * 读取则通过原子引用保证跨线程可见性。</p>
 */
public final class ClockStatistics {

    private final AtomicLong rollbackCount = new AtomicLong();
    private final AtomicLong maxRollbackMillis = new AtomicLong();
    private final AtomicLong totalWaitMillis = new AtomicLong();
    private final AtomicLong totalSequencesGenerated = new AtomicLong();
    private final AtomicLong recoveryCount = new AtomicLong();
    private final AtomicReference<RecoveryPoint> lastRecoveryPoint = new AtomicReference<>();
    private final CopyOnWriteArrayList<RecoveryPoint> recoveryPoints = new CopyOnWriteArrayList<>();

    void recordRollback(long rollbackMillis) {
        rollbackCount.incrementAndGet();
        maxRollbackMillis.accumulateAndGet(rollbackMillis, Math::max);
    }

    void addWaitMillis(long millis) {
        totalWaitMillis.addAndGet(millis);
    }

    void incrementSequences() {
        totalSequencesGenerated.incrementAndGet();
    }

    void recordRecovery(RecoveryPoint point) {
        recoveryCount.incrementAndGet();
        lastRecoveryPoint.set(point);
        recoveryPoints.add(point);
    }

    /**
     * 检测到的回拨次数（含等待期间观测到的进一步回退）。
     */
    public long rollbackCount() {
        return rollbackCount.get();
    }

    /**
     * 观测到的最大单次回拨幅度（毫秒）。
     */
    public long maxRollbackMillis() {
        return maxRollbackMillis.get();
    }

    /**
     * 小幅回拨自愈过程中累计的补偿等待时长（毫秒）。
     */
    public long totalWaitMillis() {
        return totalWaitMillis.get();
    }

    /**
     * 通过 {@link MonotonicClock#nextSequence()} 生成的序号总数。
     */
    public long totalSequencesGenerated() {
        return totalSequencesGenerated.get();
    }

    /**
     * 已记录的恢复点数量。
     */
    public long recoveryCount() {
        return recoveryCount.get();
    }

    /**
     * 最近一次恢复点；尚未恢复过时为 {@code null}。
     */
    public RecoveryPoint lastRecoveryPoint() {
        return lastRecoveryPoint.get();
    }

    /**
     * 全部恢复点的只读快照，按发生顺序排列。
     */
    public List<RecoveryPoint> recoveryPoints() {
        return List.copyOf(recoveryPoints);
    }
}
