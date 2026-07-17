package com.eventledger.gateway.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * Custom business metrics (spec requirement: at least one custom metric).
 *
 * Micrometer registers these with the Actuator MeterRegistry, so they are
 * visible at GET /metrics/{name} alongside the built-in JVM/HTTP metrics.
 * In production the same registry would be scraped by Prometheus — Micrometer
 * is the facade, the backend is pluggable.
 *
 * Chosen metrics tell the operational story of this system:
 *  - events.received    how much work arrives
 *  - events.applied     how much completes end-to-end
 *  - events.duplicate   how noisy the upstream systems are
 *  - account.service.call.duration  latency (and count) of the downstream
 *    dependency — the first thing to look at when things slow down
 * A growing gap between received and applied = events piling up PENDING,
 * i.e. the downstream is struggling. That one comparison is a health signal.
 */
@Component
public class EventMetrics {

    private final Counter received;
    private final Counter applied;
    private final Counter duplicates;
    private final Timer accountServiceCalls;

    public EventMetrics(MeterRegistry registry) {
        this.received = Counter.builder("events.received")
                .description("Transaction events received by POST /events (incl. duplicates)")
                .register(registry);
        this.applied = Counter.builder("events.applied")
                .description("Events successfully applied to the Account Service")
                .register(registry);
        this.duplicates = Counter.builder("events.duplicate")
                .description("Idempotent duplicate submissions (same eventId, same payload)")
                .register(registry);
        this.accountServiceCalls = Timer.builder("account.service.call.duration")
                .description("Latency of Gateway -> Account Service transaction calls")
                .register(registry);
    }

    public void markReceived() {
        received.increment();
    }

    public void markApplied() {
        applied.increment();
    }

    public void markDuplicate() {
        duplicates.increment();
    }

    /** Times the downstream call; records duration even when the call throws. */
    public void timeDownstreamCall(Runnable call) {
        accountServiceCalls.record(call);
    }
}
