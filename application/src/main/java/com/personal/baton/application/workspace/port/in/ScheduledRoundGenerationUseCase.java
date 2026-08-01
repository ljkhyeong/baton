package com.personal.baton.application.workspace.port.in;

import java.util.List;
import java.util.UUID;

public interface ScheduledRoundGenerationUseCase {

    GenerationResult generateDueRounds();

    record GenerationResult(int candidateCount, List<UUID> failedSeasonIds) {

        public GenerationResult {
            failedSeasonIds = List.copyOf(failedSeasonIds);
        }

        public boolean hasFailures() {
            return !failedSeasonIds.isEmpty();
        }
    }
}
