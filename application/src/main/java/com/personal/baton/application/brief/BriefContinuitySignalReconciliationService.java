package com.personal.baton.application.brief;

import com.personal.baton.application.brief.port.in.ReconcileBriefContinuitySignalsUseCase;
import com.personal.baton.application.brief.port.out.BriefContinuitySignalStorePort;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.stereotype.Service;

@Service
public class BriefContinuitySignalReconciliationService
        implements ReconcileBriefContinuitySignalsUseCase {

    private static final Log log = LogFactory.getLog(
            BriefContinuitySignalReconciliationService.class
    );

    private final BriefContinuitySignalStorePort storePort;
    private final BriefContinuitySignalReconciliationWorker worker;

    public BriefContinuitySignalReconciliationService(
            BriefContinuitySignalStorePort storePort,
            BriefContinuitySignalReconciliationWorker worker
    ) {
        this.storePort = storePort;
        this.worker = worker;
    }

    @Override
    public ReconciliationResult reconcileAll() {
        List<BriefContinuitySignalScope> candidates = storePort.findReconciliationScopes();
        List<UUID> failedSeasonIds = new ArrayList<>();
        int appendedCount = 0;
        for (BriefContinuitySignalScope candidate : candidates) {
            try {
                appendedCount += worker.reconcile(candidate.teamId(), candidate.seasonId());
            } catch (RuntimeException exception) {
                failedSeasonIds.add(candidate.seasonId());
                log.error(
                        "BRIEF 연속성 신호 재조정에 실패했습니다. seasonId="
                                + candidate.seasonId(),
                        exception
                );
            }
        }
        return new ReconciliationResult(
                candidates.size(),
                appendedCount,
                List.copyOf(failedSeasonIds)
        );
    }
}
