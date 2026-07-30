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
        scheduledRoundGenerationUseCase.generateDueRounds();
    }
}
