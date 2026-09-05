package com.personal.baton.application.workspace;

import org.springframework.stereotype.Component;
import com.personal.baton.application.workspace.error.WorkspaceNotFoundException;
import com.personal.baton.application.workspace.port.out.WorkspaceOperationsRepository;
import com.personal.baton.domain.workspace.RoutineExecution;
import com.personal.baton.domain.workspace.SeasonRound;
import java.util.UUID;

@Component
final class WorkspaceSeasonRoundResolver {

    private final WorkspaceOperationsRepository repository;

    WorkspaceSeasonRoundResolver(WorkspaceOperationsRepository repository) {
        this.repository = repository;
    }

    SeasonRound requireForUpdate(UUID seasonId, UUID roundId) {
        return repository.findSeasonRoundBySeasonIdAndIdForUpdate(seasonId, roundId)
                .orElseThrow(this::roundNotFound);
    }

    SeasonRound requireActiveForUpdate(UUID seasonId, UUID roundId) {
        SeasonRound round = requireForUpdate(seasonId, roundId);
        requireActive(round);
        return round;
    }

    RoutineExecution requireExecutionInActiveRoundWithSharedLock(
            UUID seasonId,
            UUID roundId,
            UUID executionId
    ) {
        SeasonRound round = repository.findSeasonRoundBySeasonIdAndIdWithSharedLock(
                        seasonId,
                        roundId
                )
                .orElseThrow(this::roundNotFound);
        requireActive(round);
        return repository.findRoutineExecutionById(executionId)
                .filter(execution -> execution.getSeasonRoundId().equals(roundId))
                .orElseThrow(() -> new WorkspaceNotFoundException(
                        "ROUTINE_EXECUTION_NOT_FOUND",
                        "반복 업무 실행 기록을 찾을 수 없습니다"
                ));
    }

    private void requireActive(SeasonRound round) {
        if (round.getArchivedAt() != null) {
            throw roundNotFound();
        }
    }

    private WorkspaceNotFoundException roundNotFound() {
        return new WorkspaceNotFoundException(
                "SEASON_ROUND_NOT_FOUND",
                "회차를 찾을 수 없습니다"
        );
    }
}
