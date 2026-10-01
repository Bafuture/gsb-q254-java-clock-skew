package com.example.gsb.clock;

/**
 * 一次回拨恢复点：墙上时钟重新追上（并超过）逻辑时间的时刻。
 *
 * @param logicalMillis 恢复时的逻辑（单调）时间
 * @param wallMillis    恢复时的墙上时钟时间
 */
public record RecoveryPoint(long logicalMillis, long wallMillis) {
}
