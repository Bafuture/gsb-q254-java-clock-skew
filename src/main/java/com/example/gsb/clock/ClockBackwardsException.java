package com.example.gsb.clock;

/**
 * 检测到大幅时钟回拨（当前偏移超过容忍阈值）时抛出。
 *
 * <p>组件拒绝在偏移过大时静默返回可能错误的时间，而是以该异常明确失败，
 * 异常中携带当前偏移、阈值以及逻辑/墙上时钟读数，便于定位与告警。</p>
 */
public class ClockBackwardsException extends RuntimeException {

    private final long currentOffsetMillis;
    private final long thresholdMillis;
    private final long logicalMillis;
    private final long wallMillis;

    public ClockBackwardsException(long currentOffsetMillis, long thresholdMillis,
                                   long logicalMillis, long wallMillis) {
        super("Clock moved backwards beyond tolerance: current offset " + currentOffsetMillis
                + "ms exceeds threshold " + thresholdMillis + "ms (logical=" + logicalMillis
                + ", wall=" + wallMillis + "). Refusing to return a potentially wrong timestamp.");
        this.currentOffsetMillis = currentOffsetMillis;
        this.thresholdMillis = thresholdMillis;
        this.logicalMillis = logicalMillis;
        this.wallMillis = wallMillis;
    }

    /**
     * 当前偏移：墙上时钟落后逻辑时间的毫秒数。
     */
    public long currentOffsetMillis() {
        return currentOffsetMillis;
    }

    /**
     * 小幅回拨容忍阈值，超过即视为大幅回拨。
     */
    public long thresholdMillis() {
        return thresholdMillis;
    }

    /**
     * 抛出异常时的逻辑（单调）时间。
     */
    public long logicalMillis() {
        return logicalMillis;
    }

    /**
     * 抛出异常时的墙上时钟读数。
     */
    public long wallMillis() {
        return wallMillis;
    }
}
