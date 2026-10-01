package com.example.gsb.clock;

/**
 * 基于 {@link Thread#sleep(long)} 的默认等待策略。
 */
public final class SystemSleeper implements Sleeper {

    @Override
    public void sleep(long millis) throws InterruptedException {
        Thread.sleep(millis);
    }
}
