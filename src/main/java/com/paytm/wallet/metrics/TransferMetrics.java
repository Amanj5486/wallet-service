package com.paytm.wallet.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@Slf4j
public class TransferMetrics {
    private final MeterRegistry meterRegistry;
    private final Counter transfersCreatedCounter;
    private final Counter transfersDeclinedCounter;
    private final Counter transfersIdempotentReplaysCounter;
    private final Counter walletsCreatedCounter;
    private final Counter walletsGetOrCreateHitsCounter;
    private final Timer transferLatencyTimer;

    public TransferMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;

        this.transfersCreatedCounter = Counter.builder("transfers.created.total")
            .description("Total number of transfers created")
            .register(meterRegistry);

        this.transfersDeclinedCounter = Counter.builder("transfers.declined.insufficient_funds.total")
            .description("Total number of transfers declined due to insufficient funds")
            .register(meterRegistry);

        this.transfersIdempotentReplaysCounter = Counter.builder("transfers.idempotent_replays.total")
            .description("Total number of idempotent transfer replays")
            .register(meterRegistry);

        this.walletsCreatedCounter = Counter.builder("wallets.created.total")
            .description("Total number of wallets created")
            .register(meterRegistry);

        this.walletsGetOrCreateHitsCounter = Counter.builder("wallets.get_or_create_hits.total")
            .description("Total number of get-or-create hits (existing wallet returned)")
            .register(meterRegistry);

        this.transferLatencyTimer = Timer.builder("transfers.latency.ms")
            .description("Transfer operation latency in milliseconds")
            .register(meterRegistry);
    }

    public void recordTransferCreated() {
        transfersCreatedCounter.increment();
    }

    public void recordTransferDeclined() {
        transfersDeclinedCounter.increment();
    }

    public void recordIdempotentReplay() {
        transfersIdempotentReplaysCounter.increment();
    }

    public void recordWalletCreated() {
        walletsCreatedCounter.increment();
    }

    public void recordWalletGetOrCreateHit() {
        walletsGetOrCreateHitsCounter.increment();
    }

    public void recordTransferLatency(long durationMs) {
        transferLatencyTimer.record(Duration.ofMillis(durationMs));
    }
}
