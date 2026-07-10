package com.archiveledger.ledger;

import com.archiveledger.ledger.common.LedgerModels.RuntimeTickResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RuntimeWorkLoop {
    private static final Logger log = LoggerFactory.getLogger(RuntimeWorkLoop.class);

    private final LedgerService ledger;
    private final boolean enabled;
    private final int maxEventsPerTick;
    private final int maxBacklogPerTick;

    public RuntimeWorkLoop(LedgerService ledger,
                           @Value("${archive.runtime.autorun.enabled:true}") boolean enabled,
                           @Value("${archive.runtime.max-events-per-tick:10}") int maxEventsPerTick,
                           @Value("${archive.runtime.max-backlog-per-tick:50}") int maxBacklogPerTick) {
        this.ledger = ledger;
        this.enabled = enabled;
        this.maxEventsPerTick = maxEventsPerTick;
        this.maxBacklogPerTick = maxBacklogPerTick;
    }

    @Scheduled(
            initialDelayString = "${archive.runtime.initial-delay:10s}",
            fixedDelayString = "${archive.runtime.tick-interval:30s}"
    )
    public void runAutonomousTick() {
        if (!enabled) {
            return;
        }
        try {
            RuntimeTickResult result = ledger.autonomousRuntimeTick(maxEventsPerTick, maxBacklogPerTick);
            log.debug("Runtime work tick completed: tickId={}, produced={}, consumed={}, backlog={}, duplicate={}",
                    result.tickId(), result.eventsProduced(), result.eventsConsumed(), result.backlogCount(), result.duplicate());
        } catch (RuntimeException error) {
            log.warn("Runtime work tick failed: {}", error.getMessage());
        }
    }
}
