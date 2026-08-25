package com.kmultan.claims.perf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Tiny latency stats helper: percentiles over recorded millisecond samples. */
public final class Stats {
    private final List<Long> samples = Collections.synchronizedList(new ArrayList<>());

    public void record(long millis) { samples.add(millis); }
    public int count() { return samples.size(); }

    public long percentile(double p) {
        List<Long> sorted = new ArrayList<>(samples);
        Collections.sort(sorted);
        int idx = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
    }

    public String summary(String name, long wallMillis) {
        return String.format("%-32s n=%d  p50=%dms  p95=%dms  p99=%dms  max=%dms  throughput=%.1f/s",
                name, count(), percentile(50), percentile(95), percentile(99), percentile(100), count() * 1000.0 / Math.max(1, wallMillis));
    }
}
