package com.personal.baton.application.workspace;

import com.personal.baton.application.workspace.WorkspaceContentIdempotency.ContentCreationAttempt;
import com.personal.baton.application.workspace.error.SeasonRoundNameConflictException;
import com.personal.baton.application.workspace.error.WorkspaceNotFoundException;
import com.personal.baton.application.workspace.port.in.WorkspaceRoutineUseCase.CreateRoutineCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceRoutineUseCase.RoutineResult;
import com.personal.baton.application.workspace.port.in.WorkspaceRoutineUseCase.UpdateRoutineCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceSeasonRoundUseCase.CreateSeasonRoundCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceSeasonRoundUseCase.RoutineExecutionResult;
import com.personal.baton.application.workspace.port.in.WorkspaceSeasonRoundUseCase.SeasonRoundResult;
import com.personal.baton.application.workspace.port.in.WorkspaceSeasonRoundUseCase.UpdateSeasonRoundCommand;
import com.personal.baton.application.workspace.port.out.WorkspaceRepository;
import com.personal.baton.domain.workspace.ContentCreationOperation;
import com.personal.baton.domain.workspace.DomainValidationException;
import com.personal.baton.domain.workspace.RoundOrigin;
import com.personal.baton.domain.workspace.RoundSchedule;
import com.personal.baton.domain.workspace.Routine;
import com.personal.baton.domain.workspace.RoutineExecution;
import com.personal.baton.domain.workspace.Season;
import com.personal.baton.domain.workspace.SeasonRound;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

final class WorkspaceRoutineRoundLifecycle {

    private final WorkspaceRepository repository;
    private final Clock clock;
    private final WorkspaceResultMapper resultMapper;
    private final WorkspaceContentIdempotency contentIdempotency;
    private final WorkspaceRoleReferenceValidator roleReferenceValidator;

    WorkspaceRoutineRoundLifecycle(
            WorkspaceRepository repository,
            Clock clock,
            WorkspaceResultMapper resultMapper,
            WorkspaceContentIdempotency contentIdempotency,
            WorkspaceRoleReferenceValidator roleReferenceValidator
    ) {
        this.repository = repository;
        this.clock = clock;
        this.resultMapper = resultMapper;
        this.contentIdempotency = contentIdempotency;
        this.roleReferenceValidator = roleReferenceValidator;
    }

    RoutineResult createRoutine(
            UUID teamId,
            UUID seasonId,
            WorkspaceScope scope,
            String idempotencyKey,
            CreateRoutineCommand command
    ) {
        requireDeadlineRuleForEnabledSchedule(
                scope.season(),
                command.deadlineDayOffset(),
                command.deadlineTime()
        );
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
            roleReferenceValidator.requireOwned(teamId, seasonId, existing.getOwnerRoleId());
            return resultMapper.toRoutineResult(existing);
        }
        roleReferenceValidator.requireOwned(teamId, seasonId, routine.getOwnerRoleId());
        contentIdempotency.reserve(attempt);
        return resultMapper.toRoutineResult(repository.saveRoutine(routine));
    }

    RoutineResult updateRoutine(
            UUID teamId,
            UUID seasonId,
            WorkspaceScope scope,
            UUID routineId,
            UpdateRoutineCommand command
    ) {
        Routine routine = repository.findRoutineById(routineId)
                .filter(found -> found.getSeasonId().equals(seasonId))
                .orElseThrow(() -> notFound("ROUTINE_NOT_FOUND", "루틴을 찾을 수 없습니다"));
        roleReferenceValidator.requireOwned(teamId, seasonId, command.ownerRoleId());
        requireDeadlineRuleForEnabledSchedule(
                scope.season(),
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

    SeasonRoundResult createSeasonRound(
            UUID teamId,
            UUID seasonId,
            WorkspaceScope scope,
            String idempotencyKey,
            CreateSeasonRoundCommand command
    ) {
        SeasonRound round = SeasonRound.create(
                UUID.randomUUID(),
                seasonId,
                command.name(),
                command.meetingDate()
        );
        if (!scope.season().contains(round.getMeetingDate())) {
            throw new DomainValidationException("모임 날짜는 시즌 기간 안에 있어야 합니다");
        }
        ContentCreationAttempt attempt = contentIdempotency.prepare(
                teamId,
                seasonId,
                ContentCreationOperation.ROUND,
                idempotencyKey,
                contentIdempotency.fingerprintSeasonRoundRequest(teamId, seasonId, round),
                round.getId()
        );
        if (attempt.replayResourceId() != null) {
            SeasonRound existing = repository.findSeasonRoundById(attempt.replayResourceId())
                    .filter(found -> found.getSeasonId().equals(seasonId))
                    .orElseThrow(() ->
                            contentIdempotency.missingResource(ContentCreationOperation.ROUND));
            return resultMapper.toSeasonRoundResult(
                    existing,
                    repository.findRoutineExecutionsBySeasonRoundIds(List.of(existing.getId())),
                    scope.season()
            );
        }
        if (repository.existsSeasonRoundBySeasonIdAndName(seasonId, round.getName())) {
            throw new SeasonRoundNameConflictException();
        }

        List<RoutineExecution> executions = repository.findRoutinesBySeasonId(seasonId).stream()
                .map(routine -> RoutineExecution.snapshot(
                        UUID.randomUUID(),
                        round.getId(),
                        routine,
                        round.getMeetingDate(),
                        scope.season().getZoneId()
                ))
                .toList();
        contentIdempotency.reserve(attempt);
        SeasonRound savedRound = repository.saveSeasonRound(round);
        List<RoutineExecution> savedExecutions = repository.saveRoutineExecutions(executions);
        return resultMapper.toSeasonRoundResult(savedRound, savedExecutions, scope.season());
    }

    SeasonRoundResult updateSeasonRound(
            UUID seasonId,
            UUID roundId,
            WorkspaceScope scope,
            UpdateSeasonRoundCommand command
    ) {
        SeasonRound round = requireActiveSeasonRoundForUpdate(seasonId, roundId);
        if (round.getOrigin() == RoundOrigin.AUTOMATIC) {
            throw new DomainValidationException("자동 생성된 회차의 날짜와 이름은 수정할 수 없습니다");
        }
        String normalizedName = SeasonRound.normalizeName(command.name());
        if (!scope.season().contains(command.meetingDate())) {
            throw new DomainValidationException("모임 날짜는 시즌 기간 안에 있어야 합니다");
        }
        if (repository.existsSeasonRoundBySeasonIdAndNameAndIdNot(
                seasonId,
                normalizedName,
                round.getId()
        )) {
            throw new SeasonRoundNameConflictException();
        }
        round.update(normalizedName, command.meetingDate());
        SeasonRound saved = repository.saveSeasonRound(round);
        List<RoutineExecution> executions = repository.findRoutineExecutionsBySeasonRoundIds(
                List.of(saved.getId())
        );
        for (RoutineExecution execution : executions) {
            execution.reschedule(command.meetingDate(), scope.season().getZoneId());
        }
        List<RoutineExecution> savedExecutions = repository.saveRoutineExecutions(executions);
        return resultMapper.toSeasonRoundResult(saved, savedExecutions, scope.season());
    }

    SeasonRoundResult updateSeasonRoundArchive(
            UUID seasonId,
            UUID roundId,
            WorkspaceScope scope,
            boolean archived
    ) {
        SeasonRound round = requireSeasonRoundForUpdate(seasonId, roundId);
        round.updateArchive(archived, Instant.now(clock));
        SeasonRound saved = repository.saveSeasonRound(round);
        return resultMapper.toSeasonRoundResult(
                saved,
                repository.findRoutineExecutionsBySeasonRoundIdWithSharedLock(saved.getId()),
                scope.season()
        );
    }

    RoutineExecutionResult updateRoutineExecutionCompletion(
            UUID seasonId,
            UUID roundId,
            UUID executionId,
            WorkspaceScope scope,
            boolean completed
    ) {
        requireActiveSeasonRoundWithSharedLock(seasonId, roundId);
        RoutineExecution execution = repository.findRoutineExecutionById(executionId)
                .filter(found -> found.getSeasonRoundId().equals(roundId))
                .orElseThrow(() -> notFound(
                        "ROUTINE_EXECUTION_NOT_FOUND",
                        "루틴 실행 기록을 찾을 수 없습니다"
                ));
        execution.updateCompletion(completed);
        return resultMapper.toRoutineExecutionResult(
                repository.saveRoutineExecution(execution),
                scope.season().getZoneId()
        );
    }

    private SeasonRound requireSeasonRoundForUpdate(UUID seasonId, UUID roundId) {
        return repository.findSeasonRoundBySeasonIdAndIdForUpdate(seasonId, roundId)
                .orElseThrow(() -> notFound("SEASON_ROUND_NOT_FOUND", "회차를 찾을 수 없습니다"));
    }

    private SeasonRound requireActiveSeasonRoundForUpdate(UUID seasonId, UUID roundId) {
        SeasonRound round = requireSeasonRoundForUpdate(seasonId, roundId);
        if (round.getArchivedAt() != null) {
            throw notFound("SEASON_ROUND_NOT_FOUND", "회차를 찾을 수 없습니다");
        }
        return round;
    }

    private SeasonRound requireActiveSeasonRoundWithSharedLock(UUID seasonId, UUID roundId) {
        SeasonRound round = repository.findSeasonRoundBySeasonIdAndIdWithSharedLock(
                        seasonId,
                        roundId
                )
                .orElseThrow(() -> notFound("SEASON_ROUND_NOT_FOUND", "회차를 찾을 수 없습니다"));
        if (round.getArchivedAt() != null) {
            throw notFound("SEASON_ROUND_NOT_FOUND", "회차를 찾을 수 없습니다");
        }
        return round;
    }

    private void requireDeadlineRuleForEnabledSchedule(
            Season season,
            Integer deadlineDayOffset,
            LocalTime deadlineTime
    ) {
        RoundSchedule schedule = season.getRoundSchedule();
        if (schedule != null
                && schedule.isEnabled()
                && (deadlineDayOffset == null || deadlineTime == null)) {
            throw new DomainValidationException(
                    "자동 회차를 사용하는 동안 루틴의 실제 마감 규칙을 제거할 수 없습니다"
            );
        }
    }

    private WorkspaceNotFoundException notFound(String code, String message) {
        return new WorkspaceNotFoundException(code, message);
    }
}
