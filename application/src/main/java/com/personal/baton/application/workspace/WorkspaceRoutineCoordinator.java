package com.personal.baton.application.workspace;

import org.springframework.stereotype.Component;
import com.personal.baton.application.workspace.WorkspaceContentIdempotency.ContentCreationAttempt;
import com.personal.baton.application.workspace.error.WorkspaceNotFoundException;
import com.personal.baton.application.workspace.port.in.WorkspaceContract.CreateRoutineCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceContract.RoutineResult;
import com.personal.baton.application.workspace.port.in.WorkspaceContract.UpdateRoutineCommand;
import com.personal.baton.application.workspace.port.out.WorkspaceRepository;
import com.personal.baton.domain.workspace.ContentCreationOperation;
import com.personal.baton.domain.workspace.Routine;
import com.personal.baton.domain.workspace.Season;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Component
final class WorkspaceRoutineCoordinator {

    private final WorkspaceRepository repository;
    private final Clock clock;
    private final WorkspaceContentIdempotency contentIdempotency;
    private final WorkspaceRoleResolver roleResolver;
    private final WorkspaceResultMapper resultMapper;
    private final WorkspaceRoundSchedulePolicy roundSchedulePolicy;
    private final BriefContinuitySignalRecorder briefContinuitySignalRecorder;

    WorkspaceRoutineCoordinator(
            WorkspaceRepository repository,
            Clock clock,
            WorkspaceContentIdempotency contentIdempotency,
            WorkspaceRoleResolver roleResolver,
            WorkspaceResultMapper resultMapper,
            WorkspaceRoundSchedulePolicy roundSchedulePolicy,
            BriefContinuitySignalRecorder briefContinuitySignalRecorder
    ) {
        this.repository = repository;
        this.clock = clock;
        this.contentIdempotency = contentIdempotency;
        this.roleResolver = roleResolver;
        this.resultMapper = resultMapper;
        this.roundSchedulePolicy = roundSchedulePolicy;
        this.briefContinuitySignalRecorder = briefContinuitySignalRecorder;
    }

    RoutineResult create(
            UUID teamId,
            Season season,
            String idempotencyKey,
            CreateRoutineCommand command
    ) {
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
        roundSchedulePolicy.requireDeadlineRuleForEnabledSchedule(
                season,
                routine.getDeadlineDayOffset(),
                routine.getDeadlineTime()
        );
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
        Routine routine = requireActiveRoutine(seasonId, routineId);
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

    RoutineResult updateArchive(Season season, UUID routineId, boolean archived) {
        Routine routine = requireRoutine(season.getId(), routineId);
        if (!archived) {
            roundSchedulePolicy.requireDeadlineRuleForEnabledSchedule(
                    season,
                    routine.getDeadlineDayOffset(),
                    routine.getDeadlineTime()
            );
        }
        boolean changed = (routine.getArchivedAt() != null) != archived;
        routine.updateArchive(archived, Instant.now(clock));
        Routine saved = repository.saveRoutine(routine);
        if (changed) {
            briefContinuitySignalRecorder.reconcileSeason(season.getTeamId(), season.getId());
        }
        return resultMapper.toRoutineResult(saved);
    }

    private Routine requireRoutine(UUID seasonId, UUID routineId) {
        return repository.findRoutineById(routineId)
                .filter(found -> found.getSeasonId().equals(seasonId))
                .orElseThrow(this::routineNotFound);
    }

    private Routine requireActiveRoutine(UUID seasonId, UUID routineId) {
        Routine routine = requireRoutine(seasonId, routineId);
        if (routine.getArchivedAt() != null) {
            throw routineNotFound();
        }
        return routine;
    }

    private WorkspaceNotFoundException routineNotFound() {
        return new WorkspaceNotFoundException(
                "ROUTINE_NOT_FOUND",
                "루틴을 찾을 수 없습니다"
        );
    }

}
