package com.example.gsb.clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SequenceGeneratorTest {

    @Test
    void idsAreStrictlyIncreasingAndUniqueWithinSameMillisecond() {
        ManualTimeSource wall = new ManualTimeSource(5_000L);
        MonotonicClock clock = new MonotonicClock(wall, new RecordingSleeper());
        SequenceGenerator generator = new SequenceGenerator(clock);

        long previous = Long.MIN_VALUE;
        Set<Long> ids = new HashSet<>();
        for (int i = 0; i < 1_000; i++) {
            long id = generator.nextId();
            assertThat(id).isGreaterThan(previous);
            assertThat(ids.add(id)).as("id must be unique").isTrue();
            assertThat(SequenceGenerator.extractTimestamp(id)).isEqualTo(5_000L);
            assertThat(SequenceGenerator.extractSequence(id)).isEqualTo(i);
            previous = id;
        }

        assertThat(clock.stats().generatedSequenceCount()).isEqualTo(1_000L);
    }

    @Test
    void idsStayStrictlyIncreasingAcrossSmallRollback() {
        ManualTimeSource wall = new ManualTimeSource(5_000L);
        MonotonicClock clock = new MonotonicClock(wall, new RecordingSleeper());
        SequenceGenerator generator = new SequenceGenerator(clock);

        long first = generator.nextId();
        long second = generator.nextId();

        wall.set(4_990L); // 10 ms jitter while ids are being generated

        long afterRollback1 = generator.nextId();
        long afterRollback2 = generator.nextId();

        assertThat(second).isGreaterThan(first);
        assertThat(afterRollback1).isGreaterThan(second);
        assertThat(afterRollback2).isGreaterThan(afterRollback1);
        // Logical time compensated forward to 5001, so the id embeds it.
        assertThat(SequenceGenerator.extractTimestamp(afterRollback1)).isEqualTo(5_001L);
        assertThat(clock.stats().generatedSequenceCount()).isEqualTo(4L);
    }

    @Test
    void perMillisecondSequenceOverflowWaitsForNextTimestamp() {
        // Wall clock that only advances after the generator notices the stall.
        TimeSource stallingWall = new TimeSource() {
            private long value = 7_000L;
            private int reads;

            @Override
            public long currentTimeMillis() {
                if (++reads > 4_200) {
                    value++;
                }
                return value;
            }
        };
        MonotonicClock clock = new MonotonicClock(stallingWall, new RecordingSleeper());
        SequenceGenerator generator = new SequenceGenerator(clock);

        long previous = Long.MIN_VALUE;
        Set<Long> ids = new HashSet<>();
        for (int i = 0; i < 5_000; i++) {
            long id = generator.nextId();
            assertThat(id).isGreaterThan(previous);
            assertThat(ids.add(id)).isTrue();
            previous = id;
        }

        // More ids than one millisecond can hold: timestamps must have advanced.
        assertThat(SequenceGenerator.extractTimestamp(previous)).isGreaterThan(7_000L);
    }

    @Test
    void largeRollbackPropagatesFailureInsteadOfEmittingIds() {
        ManualTimeSource wall = new ManualTimeSource(5_000L);
        MonotonicClock clock = new MonotonicClock(wall, new RecordingSleeper());
        SequenceGenerator generator = new SequenceGenerator(clock);

        generator.nextId();
        wall.set(1_000L); // 4000 ms rollback, above the default 100 ms threshold

        assertThatThrownBy(generator::nextId).isInstanceOf(ClockRollbackException.class);
        assertThat(clock.stats().generatedSequenceCount()).isEqualTo(1L);
    }

    @Test
    void idLayoutPacksTimestampAndSequence() {
        ManualTimeSource wall = new ManualTimeSource(123_456L);
        MonotonicClock clock = new MonotonicClock(wall, new RecordingSleeper());
        SequenceGenerator generator = new SequenceGenerator(clock);

        long id1 = generator.nextId();
        long id2 = generator.nextId();

        assertThat(SequenceGenerator.extractTimestamp(id1)).isEqualTo(123_456L);
        assertThat(SequenceGenerator.extractSequence(id1)).isZero();
        assertThat(SequenceGenerator.extractTimestamp(id2)).isEqualTo(123_456L);
        assertThat(SequenceGenerator.extractSequence(id2)).isEqualTo(1L);
    }
}
