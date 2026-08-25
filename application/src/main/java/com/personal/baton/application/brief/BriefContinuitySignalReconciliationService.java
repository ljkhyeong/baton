package com.personal.baton.application.brief;

import com.personal.baton.application.brief.port.in.ReconcileBriefContinuitySignalsUseCase;
import com.personal.baton.application.workspace.BriefContinuitySignalRecorder;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BriefContinuitySignalReconciliationService
        implements ReconcileBriefContinuitySignalsUseCase {

    private final BriefContinuitySignalRecorder recorder;

    public BriefContinuitySignalReconciliationService(BriefContinuitySignalRecorder recorder) {
        this.recorder = recorder;
    }

    @Override
    @Transactional
    public int reconcile(UUID teamId, UUID seasonId) {
        return recorder.reconcileSeason(teamId, seasonId);
    }
}
