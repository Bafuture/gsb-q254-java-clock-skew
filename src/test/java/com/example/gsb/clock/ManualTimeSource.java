package com.example.gsb.clock;

/** Controllable wall clock for tests: settable, advanceable, optionally auto-advancing per read. */
final class ManualTimeSource implements TimeSource {

    private long now;
    private final long autoAdvanceMillis;

    ManualTimeSource() {
        this(0L, 0L);
    }

    ManualTimeSource(long initialMillis) {
        this(initialMillis, 0L);
    }

    ManualTimeSource(long initialMillis, long autoAdvanceMillis) {
        this.now = initialMillis;
        this.autoAdvanceMillis = autoAdvanceMillis;
    }

    @Override
    public long currentTimeMillis() {
        if (autoAdvanceMillis != 0L) {
            now += autoAdvanceMillis;
        }
        return now;
    }

    void set(long millis) {
        this.now = millis;
    }

    void advance(long millis) {
        this.now += millis;
    }
}
