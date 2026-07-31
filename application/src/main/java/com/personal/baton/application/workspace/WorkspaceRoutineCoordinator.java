package com.personal.baton.application.workspace;

import com.personal.baton.application.workspace.WorkspaceContentIdempotency.ContentCreationAttempt;
import com.personal.baton.application.workspace.error.WorkspaceNotFoundException;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.CreateRoutineCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.RoutineResult;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.UpdateRoutineCommand;
import com.personal.baton.application.workspace.port.out.WorkspaceRepository;
import com.personal.baton.domain.workspace.ContentCreationOperation;
import com.personal.baton.domain.workspace.Routine;
import com.personal.baton.domain.workspace.Season;
import java.util.UUID;

final class WorkspaceRoutineCoordinator {

    private final WorkspaceRepository repository;
    private final WorkspaceContentIdempotency contentIdempotency;
    private final WorkspaceRoleResolver roleResolver;
    private final WorkspaceResultMapper resultMapper;
    private final WorkspaceRoundSchedulePolicy roundSchedulePolicy;

    WorkspaceRoutineCoordinator(
            WorkspaceRepository repository,
            WorkspaceContentIdempotency contentIdempotency,
            WorkspaceRoleResolver roleResolver,
            WorkspaceResultMapper resultMapper,
            WorkspaceRoundSchedulePolicy roundSchedulePolicy
    ) {
        this.repository = repository;
        this.contentIdempotency = contentIdempotency;
        this.roleResolver = roleResolver;
        this.resultMapper = resultMapper;
        this.roundSchedulePolicy = roundSchedulePolicy;
    }

    RoutineResult create(
            UUID teamId,
            Season season,
            String idempotencyKey,
            CreateRoutineCommand command
    ) {
        roundSchedulePolicy.requireDeadlineRuleForEnabledSchedule(
                season,
                command.deadlineDayOffset(),
                command.deadlineTime()
        );
        UUID seasonId = season.getId();
        Routine routine = Routine.create(
                UUID.randomUUID(),
                seasonId,
                command.title(),
                command.phase(),
                command.dueLabel(),
                command.ownerRoleId(),
                command.detail(),
                command.deadlineDayOffset(),
                command.deadlineTime()
        );
        ContentCreationAttempt attempt = contentIdempotency.prepare(
                teamId,
                seasonId,
                ContentCreationOperation.ROUTINE,
                idempotencyKey,
                contentIdempotency.fingerprintRoutineRequest(teamId, seasonId, routine),
                routine.getId()
        );
        if (attempt.replayResourceId() != null) {
            Routine existing = repository.findRoutineById(attempt.replayResourceId())
                    .filter(found -> found.getSeasonId().equals(seasonId))
                    .orElseThrow(() ->
                            contentIdempotency.missingResource(ContentCreationOperation.ROUTINE));
            roleResolver.requireRole(teamId, seasonId, existing.getOwnerRoleId());
            return resultMapper.toRoutineResult(existing);
        }
        roleResolver.requireRole(teamId, seasonId, routine.getOwnerRoleId());
        contentIdempotency.reserve(attempt);
        return resultMapper.toRoutineResult(repository.saveRoutine(routine));
    }

    RoutineResult update(
            UUID teamId,
            Season season,
            UUID routineId,
            UpdateRoutineCommand command
    ) {
        UUID seasonId = season.getId();
        Routine routine = repository.findRoutineById(routineId)
                .filter(found -> found.getSeasonId().equals(seasonId))
                .orElseThrow(() -> new WorkspaceNotFoundException(
                        "ROUTINE_NOT_FOUND",
                        "루틴을 찾을 수 없습니다"
                ));
        roleResolver.requireRole(teamId, seasonId, command.ownerRoleId());
        roundSchedulePolicy.requireDeadlineRuleForEnabledSchedule(
                season,
                command.deadlineDayOffset(),
                command.deadlineTime()
        );
        routine.update(
                command.title(),
                command.phase(),
                command.dueLabel(),
                command.ownerRoleId(),
                command.detail(),
                command.deadlineDayOffset(),
                command.deadlineTime()
        );
        return resultMapper.toRoutineResult(repository.saveRoutine(routine));
    }

}
