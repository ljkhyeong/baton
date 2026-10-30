package com.personal.baton.application.watch.port.in;

import java.util.List;
import java.util.UUID;

public interface ReconcileWatchMonitorsUseCase {

    ReconciliationResult reconcile();

    record ReconciliationResult(
            int candidateCount,
            int appendedCount,
            List<UUID> failedResourceIds
    ) {

        public boolean hasFailures() {
            return !failedResourceIds.isEmpty();
        }
    }
}
