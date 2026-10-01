package com.example.gsb.clock;

/**
 * 测试用墙上时钟：可随意拨动以模拟校时回拨与虚拟机迁移。
 */
final class MutableClock implements ClockSource {

    private long now;

    MutableClock(long initialMillis) {
        this.now = initialMillis;
    }

    @Override
    public long currentTimeMillis() {
        return now;
    }

    void set(long millis) {
        this.now = millis;
    }

    void advance(long deltaMillis) {
        this.now += deltaMillis;
    }
}
