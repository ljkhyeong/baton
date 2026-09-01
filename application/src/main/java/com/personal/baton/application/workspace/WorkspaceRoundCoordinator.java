package com.personal.baton.application.workspace;

import org.springframework.stereotype.Component;
import com.personal.baton.application.calendar.CalendarChangeRecorder;
import com.personal.baton.application.workspace.WorkspaceContentIdempotency.ContentCreationAttempt;
import com.personal.baton.application.workspace.port.in.WorkspaceContract.CreateSeasonRoundCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceContract.RoutineExecutionResult;
import com.personal.baton.application.workspace.port.in.WorkspaceContract.SeasonRoundResult;
import com.personal.baton.application.workspace.port.in.WorkspaceContract.UpdateSeasonRoundCommand;
import com.personal.baton.application.workspace.port.out.WorkspaceRepository;
import com.personal.baton.domain.workspace.ContentCreationOperation;
import com.personal.baton.domain.workspace.DomainValidationException;
import com.personal.baton.domain.workspace.RoutineExecution;
import com.personal.baton.domain.workspace.RoutineStatus;
import com.personal.baton.domain.workspace.Season;
import com.personal.baton.domain.workspace.SeasonRound;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Component
final class WorkspaceRoundCoordinator {

    private final WorkspaceRepository repository;
    private final Clock clock;
    private final WorkspaceContentIdempotency contentIdempotency;
    private final WorkspaceResultMapper resultMapper;
    private final WorkspaceSeasonRoundResolver roundResolver;
    private final RoutineExecutionSnapshotFactory snapshotFactory;
    private final CalendarChangeRecorder calendarChangeRecorder;
    private final BriefContinuitySignalRecorder briefContinuitySignalRecorder;

    WorkspaceRoundCoordinator(
            WorkspaceRepository repository,
            Clock clock,
            WorkspaceContentIdempotency contentIdempotency,
            WorkspaceResultMapper resultMapper,
            WorkspaceSeasonRoundResolver roundResolver,
            RoutineExecutionSnapshotFactory snapshotFactory,
            CalendarChangeRecorder calendarChangeRecorder,
            BriefContinuitySignalRecorder briefContinuitySignalRecorder
    ) {
        this.repository = repository;
        this.clock = clock;
        this.contentIdempotency = contentIdempotency;
        this.resultMapper = resultMapper;
        this.roundResolver = roundResolver;
        this.snapshotFactory = snapshotFactory;
        this.calendarChangeRecorder = calendarChangeRecorder;
        this.briefContinuitySignalRecorder = briefContinuitySignalRecorder;
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
        if (!season.contains(round.getMeetingDate())) {
            throw new DomainValidationException("모임 날짜는 시즌 기간 안에 있어야 합니다");
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
        calendarChangeRecorder.record(season, savedRound, savedExecutions);
        briefContinuitySignalRecorder.reconcileSeason(teamId, seasonId);
        return resultMapper.toSeasonRoundResult(savedRound, savedExecutions, season);
    }

    SeasonRoundResult update(
            Season season,
            UUID roundId,
            UpdateSeasonRoundCommand command
    ) {
        UUID seasonId = season.getId();
        SeasonRound round = roundResolver.requireActiveForUpdate(seasonId, roundId);
        boolean meetingDateChanged = !Objects.equals(round.getMeetingDate(), command.meetingDate());
        round.update(command.name(), command.meetingDate());
        if (!season.contains(command.meetingDate())) {
            throw new DomainValidationException("모임 날짜는 시즌 기간 안에 있어야 합니다");
        }
        SeasonRound saved = repository.saveSeasonRound(round);
        List<RoutineExecution> executions = repository.findRoutineExecutionsBySeasonRoundIds(
                List.of(saved.getId())
        );
        if (meetingDateChanged) {
            for (RoutineExecution execution : executions) {
                execution.reschedule(command.meetingDate(), season.getZoneId());
            }
            executions = repository.saveRoutineExecutions(executions);
        }
        calendarChangeRecorder.record(season, saved, executions);
        if (meetingDateChanged) {
            briefContinuitySignalRecorder.reconcileSeason(season.getTeamId(), seasonId);
        }
        return resultMapper.toSeasonRoundResult(saved, executions, season);
    }

    SeasonRoundResult updateArchive(Season season, UUID roundId, boolean archived) {
        SeasonRound round = roundResolver.requireForUpdate(season.getId(), roundId);
        boolean changed = (round.getArchivedAt() != null) != archived;
        round.updateArchive(archived, Instant.now(clock));
        SeasonRound saved = repository.saveSeasonRound(round);
        List<RoutineExecution> executions =
                repository.findRoutineExecutionsBySeasonRoundIdWithSharedLock(saved.getId());
        calendarChangeRecorder.record(season, saved, executions);
        if (changed) {
            briefContinuitySignalRecorder.reconcileSeason(season.getTeamId(), season.getId());
        }
        return resultMapper.toSeasonRoundResult(saved, executions, season);
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
        boolean changed = (execution.getStatus() == RoutineStatus.DONE) != completed;
        execution.updateCompletion(completed);
        RoutineExecution saved = repository.saveRoutineExecution(execution);
        if (changed) {
            briefContinuitySignalRecorder.reconcileSeason(season.getTeamId(), season.getId());
        }
        return resultMapper.toRoutineExecutionResult(
                saved,
                season.getZoneId()
        );
    }
}
