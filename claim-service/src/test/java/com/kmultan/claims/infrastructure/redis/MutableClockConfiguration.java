package com.kmultan.claims.infrastructure.redis;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/** A clock the test can move, so a 60-second window can be crossed without sleeping 60 seconds. */
@TestConfiguration
public class MutableClockConfiguration {

    public static class MutableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.now());
        public void advanceSeconds(long s) { now.updateAndGet(i -> i.plusSeconds(s)); }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    }

    @Bean
    @Primary
    MutableClock mutableClock() {
        return new MutableClock();
    }
}
