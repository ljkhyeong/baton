package com.personal.baton.application.brief;

import com.personal.baton.application.workspace.BriefContinuitySignalRecorder;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BriefContinuitySignalReconciliationWorker {

    private final BriefContinuitySignalRecorder recorder;

    public BriefContinuitySignalReconciliationWorker(BriefContinuitySignalRecorder recorder) {
        this.recorder = recorder;
    }

    @Transactional
    public int reconcile(UUID teamId, UUID seasonId) {
        return recorder.reconcileSeason(teamId, seasonId);
    }
}
