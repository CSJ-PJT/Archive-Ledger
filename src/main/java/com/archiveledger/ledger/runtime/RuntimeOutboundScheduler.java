package com.archiveledger.ledger.runtime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RuntimeOutboundScheduler {
    private final RuntimeOutboundService outbound;
    private final boolean schedulerEnabled;

    public RuntimeOutboundScheduler(RuntimeOutboundService outbound,
                                    @Value("${archive-ledger.runtime-ingest.scheduler-enabled:false}") boolean schedulerEnabled) {
        this.outbound = outbound;
        this.schedulerEnabled = schedulerEnabled;
    }

    @Scheduled(initialDelayString = "${archive-ledger.runtime-ingest.initial-delay-ms:15000}",
            fixedDelayString = "${archive-ledger.runtime-ingest.fixed-delay-ms:15000}")
    public void deliverRuntimeEvents() {
        if (schedulerEnabled) {
            outbound.runDeliveryCycle();
        }
    }
}
