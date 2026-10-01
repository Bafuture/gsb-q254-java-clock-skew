package com.example.gsb.clock;

/**
 * 基于 {@link System#currentTimeMillis()} 的默认墙上时钟来源。
 */
public final class SystemClockSource implements ClockSource {

    @Override
    public long currentTimeMillis() {
        return System.currentTimeMillis();
    }
}
