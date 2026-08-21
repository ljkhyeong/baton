package com.personal.baton.application.workspace;

import com.personal.baton.application.workspace.WorkspaceContentIdempotency.ContentCreationAttempt;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.CreateSeasonRoundCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.RoutineExecutionResult;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.SeasonRoundResult;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.UpdateSeasonRoundCommand;
import com.personal.baton.application.workspace.port.out.WorkspaceRepository;
import com.personal.baton.domain.workspace.ContentCreationOperation;
import com.personal.baton.domain.workspace.DomainValidationException;
import com.personal.baton.domain.workspace.RoundOrigin;
import com.personal.baton.domain.workspace.RoutineExecution;
import com.personal.baton.domain.workspace.Season;
import com.personal.baton.domain.workspace.SeasonRound;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

final class WorkspaceRoundCoordinator {

    private final WorkspaceRepository repository;
    private final Clock clock;
    private final WorkspaceContentIdempotency contentIdempotency;
    private final WorkspaceResultMapper resultMapper;
    private final WorkspaceSeasonRoundResolver roundResolver;
    private final RoutineExecutionSnapshotFactory snapshotFactory;

    WorkspaceRoundCoordinator(
            WorkspaceRepository repository,
            Clock clock,
            WorkspaceContentIdempotency contentIdempotency,
            WorkspaceResultMapper resultMapper,
            WorkspaceSeasonRoundResolver roundResolver,
            RoutineExecutionSnapshotFactory snapshotFactory
    ) {
        this.repository = repository;
        this.clock = clock;
        this.contentIdempotency = contentIdempotency;
        this.resultMapper = resultMapper;
        this.roundResolver = roundResolver;
        this.snapshotFactory = snapshotFactory;
    }

    SeasonRoundResult create(
            UUID teamId,
            Season season,
            String idempotencyKey,
            CreateSeasonRoundCommand command
    ) {
        UUID seasonId = season.getId();
        SeasonRound round = SeasonRound.create(
                UUID.randomUUID(),
                seasonId,
                command.name(),
                command.meetingDate()
        );
        if (!season.contains(round.getMeetingDate())) {
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
                    season
            );
        }
        List<RoutineExecution> executions = snapshotFactory.snapshotAll(
                round.getId(),
                repository.findRoutinesBySeasonId(seasonId),
                round.getMeetingDate(),
                season.getZoneId()
        );
        contentIdempotency.reserve(attempt);
        SeasonRound savedRound = repository.saveSeasonRound(round);
        List<RoutineExecution> savedExecutions = repository.saveRoutineExecutions(executions);
        return resultMapper.toSeasonRoundResult(savedRound, savedExecutions, season);
    }

    SeasonRoundResult update(
            Season season,
            UUID roundId,
            UpdateSeasonRoundCommand command
    ) {
        UUID seasonId = season.getId();
        SeasonRound round = roundResolver.requireActiveForUpdate(seasonId, roundId);
        if (round.getOrigin() == RoundOrigin.AUTOMATIC) {
            throw new DomainValidationException("자동 생성된 회차의 날짜와 이름은 수정할 수 없습니다");
        }
        if (!season.contains(command.meetingDate())) {
            throw new DomainValidationException("모임 날짜는 시즌 기간 안에 있어야 합니다");
        }
        round.update(command.name(), command.meetingDate());
        SeasonRound saved = repository.saveSeasonRound(round);
        List<RoutineExecution> executions = repository.findRoutineExecutionsBySeasonRoundIds(
                List.of(saved.getId())
        );
        for (RoutineExecution execution : executions) {
            execution.reschedule(command.meetingDate(), season.getZoneId());
        }
        List<RoutineExecution> savedExecutions = repository.saveRoutineExecutions(executions);
        return resultMapper.toSeasonRoundResult(saved, savedExecutions, season);
    }

    SeasonRoundResult updateArchive(Season season, UUID roundId, boolean archived) {
        SeasonRound round = roundResolver.requireForUpdate(season.getId(), roundId);
        round.updateArchive(archived, Instant.now(clock));
        SeasonRound saved = repository.saveSeasonRound(round);
        return resultMapper.toSeasonRoundResult(
                saved,
                repository.findRoutineExecutionsBySeasonRoundIdWithSharedLock(saved.getId()),
                season
        );
    }

    RoutineExecutionResult updateExecutionCompletion(
            Season season,
            UUID roundId,
            UUID executionId,
            boolean completed
    ) {
        RoutineExecution execution = roundResolver.requireExecutionInActiveRoundWithSharedLock(
                season.getId(),
                roundId,
                executionId
        );
        execution.updateCompletion(completed);
        return resultMapper.toRoutineExecutionResult(
                repository.saveRoutineExecution(execution),
                season.getZoneId()
        );
    }
}
