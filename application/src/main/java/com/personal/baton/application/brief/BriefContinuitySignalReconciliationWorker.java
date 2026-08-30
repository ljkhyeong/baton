package com.personal.baton.application.brief;

import com.personal.baton.application.workspace.BriefContinuitySignalRecorder;
import com.personal.baton.application.workspace.port.out.WorkspaceRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BriefContinuitySignalReconciliationWorker {

    private final BriefContinuitySignalRecorder recorder;
    private final WorkspaceRepository repository;

    public BriefContinuitySignalReconciliationWorker(
            BriefContinuitySignalRecorder recorder,
            WorkspaceRepository repository
    ) {
        this.recorder = recorder;
        this.repository = repository;
    }

    @Transactional
    public int reconcile(UUID teamId, UUID seasonId) {
        repository.findTeamByIdWithSharedLock(teamId)
                .orElseThrow(() -> new IllegalStateException("BRIEF 신호 재조정 팀을 찾을 수 없습니다"));
        repository.findSeasonByTeamIdAndIdForUpdate(teamId, seasonId)
                .orElseThrow(() -> new IllegalStateException("BRIEF 신호 재조정 시즌을 찾을 수 없습니다"));
        return recorder.reconcileSeason(teamId, seasonId);
    }
}
