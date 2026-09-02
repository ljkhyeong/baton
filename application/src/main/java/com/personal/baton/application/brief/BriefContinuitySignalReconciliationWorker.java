package com.personal.baton.application.brief;

import com.personal.baton.application.workspace.BriefContinuitySignalRecorder;
import com.personal.baton.application.workspace.port.out.WorkspaceAccessRepository;
import com.personal.baton.application.workspace.port.out.WorkspaceSeasonRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BriefContinuitySignalReconciliationWorker {

    private final BriefContinuitySignalRecorder recorder;
    private final WorkspaceAccessRepository accessRepository;
    private final WorkspaceSeasonRepository seasonRepository;

    public BriefContinuitySignalReconciliationWorker(
            BriefContinuitySignalRecorder recorder,
            WorkspaceAccessRepository accessRepository,
            WorkspaceSeasonRepository seasonRepository
    ) {
        this.recorder = recorder;
        this.accessRepository = accessRepository;
        this.seasonRepository = seasonRepository;
    }

    @Transactional
    public int reconcile(UUID teamId, UUID seasonId) {
        accessRepository.findTeamByIdWithSharedLock(teamId)
                .orElseThrow(() -> new IllegalStateException("BRIEF 신호 재조정 팀을 찾을 수 없습니다"));
        seasonRepository.findSeasonByTeamIdAndIdForUpdate(teamId, seasonId)
                .orElseThrow(() -> new IllegalStateException("BRIEF 신호 재조정 시즌을 찾을 수 없습니다"));
        return recorder.reconcileSeason(teamId, seasonId);
    }
}
