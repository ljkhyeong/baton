package com.personal.baton.application.brief.port.in;

import java.util.List;
import java.util.UUID;

public interface ReconcileBriefContinuitySignalsUseCase {

    ReconciliationResult reconcileAll();

    record ReconciliationResult(
            int candidateCount,
            int appendedCount,
            List<UUID> failedSeasonIds
    ) {

        public boolean hasFailures() {
            return !failedSeasonIds.isEmpty();
        }
    }
}
