package com.example.gsb.clock;

/**
 * 可注入的墙上时钟来源，便于在测试中模拟时钟回拨。
 */
@FunctionalInterface
public interface ClockSource {

    /**
     * 返回当前墙上时钟毫秒数（允许回拨）。
     */
    long currentTimeMillis();
}
