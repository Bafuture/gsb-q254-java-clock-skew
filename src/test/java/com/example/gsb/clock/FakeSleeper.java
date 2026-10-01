package com.example.gsb.clock;

/**
 * 测试用等待策略：记录等待时长；可选地在等待时推进某个 {@link MutableClock}，
 * 用于模拟"真实时间流逝后墙上时钟自然追平"。
 */
final class FakeSleeper implements Sleeper {

    private final MutableClock clockToAdvance;
    private long totalSlept;

    private FakeSleeper(MutableClock clockToAdvance) {
        this.clockToAdvance = clockToAdvance;
    }

    /**
     * 等待时同步推进给定时钟（模拟时间正常流逝）。
     */
    static FakeSleeper advancing(MutableClock clock) {
        return new FakeSleeper(clock);
    }

    /**
     * 等待不影响任何时钟（模拟墙上时钟停滞，等待预算必然耗尽）。
     */
    static FakeSleeper stuck() {
        return new FakeSleeper(null);
    }

    @Override
    public void sleep(long millis) {
        totalSlept += millis;
        if (clockToAdvance != null) {
            clockToAdvance.advance(millis);
        }
    }

    long totalSlept() {
        return totalSlept;
    }
}
