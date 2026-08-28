package com.personal.baton.bootstrap.scheduling;

import com.personal.baton.application.brief.port.in.ReconcileBriefContinuitySignalsUseCase;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "baton.brief", name = "reconciliation-interval")
class BriefContinuitySignalScheduler {

    private static final Log log = LogFactory.getLog(BriefContinuitySignalScheduler.class);

    private final ReconcileBriefContinuitySignalsUseCase reconciliationUseCase;

    BriefContinuitySignalScheduler(
            ReconcileBriefContinuitySignalsUseCase reconciliationUseCase
    ) {
        this.reconciliationUseCase = reconciliationUseCase;
    }

    @Scheduled(
            fixedDelayString = "${baton.brief.reconciliation-interval}",
            initialDelayString = "${baton.brief.reconciliation-interval}",
            scheduler = "briefTaskScheduler"
    )
    void reconcile() {
        ReconcileBriefContinuitySignalsUseCase.ReconciliationResult result =
                reconciliationUseCase.reconcileAll();
        if (result.hasFailures()) {
            throw new IllegalStateException(
                    "BRIEF 연속성 신호 재조정에 실패한 시즌이 있습니다. failed="
                            + result.failedSeasonIds().size()
                            + ", candidates=" + result.candidateCount()
            );
        }
        if (result.appendedCount() > 0) {
            log.info("BRIEF 연속성 신호 재조정이 새 이벤트를 기록했습니다. candidates="
                    + result.candidateCount() + ", appended=" + result.appendedCount());
        }
    }
}
