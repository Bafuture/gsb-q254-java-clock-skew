package com.example.gsb.clock;

/**
 * 可注入的等待策略。小幅回拨自愈时通过它等待墙上时钟追平逻辑时间，
 * 测试中可以用假的实现避免真实睡眠。
 */
@FunctionalInterface
public interface Sleeper {

    /**
     * 等待指定的毫秒数。
     */
    void sleep(long millis) throws InterruptedException;
}
