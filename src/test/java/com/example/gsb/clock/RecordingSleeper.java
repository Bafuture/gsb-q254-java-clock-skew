package com.example.gsb.clock;

import java.util.ArrayList;
import java.util.List;

/** Sleeper that records every wait and optionally advances a fake wall clock to simulate real time. */
final class RecordingSleeper implements Sleeper {

    private final ManualTimeSource timeSourceToAdvance;
    private final List<Long> sleeps = new ArrayList<>();
    private long totalSleptMillis;

    RecordingSleeper() {
        this(null);
    }

    RecordingSleeper(ManualTimeSource timeSourceToAdvance) {
        this.timeSourceToAdvance = timeSourceToAdvance;
    }

    @Override
    public void sleep(long millis) {
        sleeps.add(millis);
        totalSleptMillis += millis;
        if (timeSourceToAdvance != null) {
            timeSourceToAdvance.advance(millis);
        }
    }

    List<Long> sleeps() {
        return List.copyOf(sleeps);
    }

    long totalSleptMillis() {
        return totalSleptMillis;
    }
}
