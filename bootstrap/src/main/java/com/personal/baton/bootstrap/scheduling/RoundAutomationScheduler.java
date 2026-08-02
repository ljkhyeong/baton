package com.personal.baton.bootstrap.scheduling;

import com.personal.baton.application.workspace.port.in.ScheduledRoundGenerationUseCase;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class RoundAutomationScheduler {

    private final ScheduledRoundGenerationUseCase scheduledRoundGenerationUseCase;

    RoundAutomationScheduler(ScheduledRoundGenerationUseCase scheduledRoundGenerationUseCase) {
        this.scheduledRoundGenerationUseCase = scheduledRoundGenerationUseCase;
    }

    @Scheduled(
            fixedDelayString = "${baton.round-automation.poll-interval:PT1M}",
            initialDelayString = "${baton.round-automation.poll-interval:PT1M}"
    )
    void generateDueRounds() {
        ScheduledRoundGenerationUseCase.GenerationResult result =
                scheduledRoundGenerationUseCase.generateDueRounds();
        if (result.hasFailures()) {
            throw new IllegalStateException(
                    "자동 회차 생성에 실패한 시즌이 있습니다. failed="
                            + result.failedSeasonIds().size()
                            + ", candidates=" + result.candidateCount()
            );
        }
    }
}
