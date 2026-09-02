package com.personal.baton.application.calendar;

import com.personal.baton.application.calendar.port.out.CalendarOutboxPort;
import com.personal.baton.application.workspace.port.out.WorkspaceOperationsRepository;
import com.personal.baton.application.workspace.port.out.WorkspaceSeasonRepository;
import com.personal.baton.domain.workspace.RoutineExecution;
import com.personal.baton.domain.workspace.Season;
import com.personal.baton.domain.workspace.SeasonRound;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CalendarSnapshotBackfillWorker {

    private final WorkspaceOperationsRepository operationsRepository;
    private final WorkspaceSeasonRepository seasonRepository;
    private final CalendarOutboxPort outboxPort;
    private final CalendarSnapshotFactory snapshotFactory;
    private final Clock clock;

    public CalendarSnapshotBackfillWorker(
            WorkspaceOperationsRepository operationsRepository,
            WorkspaceSeasonRepository seasonRepository,
            CalendarOutboxPort outboxPort,
            Clock clock
    ) {
        this.operationsRepository = operationsRepository;
        this.seasonRepository = seasonRepository;
        this.outboxPort = outboxPort;
        this.snapshotFactory = new CalendarSnapshotFactory();
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void verifyTextCompatibility(CalendarBackfillCandidate candidate) {
        SeasonRound round = operationsRepository.findSeasonRoundById(candidate.roundId())
                .orElse(null);
        if (round == null) {
            return;
        }
        CalendarTextCompatibility.require(round.getId(), "summary", round.getName());
        List<RoutineExecution> executions = operationsRepository
                .findRoutineExecutionsBySeasonRoundIds(List.of(round.getId()));
        for (RoutineExecution execution : executions) {
            if (execution.getDeadlineAt() == null) {
                continue;
            }
            CalendarTextCompatibility.require(execution.getId(), "summary", execution.getTitle());
            CalendarTextCompatibility.require(
                    execution.getId(),
                    "description",
                    execution.getDetail()
            );
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int backfill(CalendarBackfillCandidate candidate) {
        SeasonRound round = operationsRepository
                .findSeasonRoundBySeasonIdAndIdForUpdate(
                        candidate.seasonId(),
                        candidate.roundId()
                )
                .orElse(null);
        if (round == null) {
            return 0;
        }
        Season season = seasonRepository.findSeasonById(candidate.seasonId())
                .orElseThrow(() -> new IllegalStateException(
                        "CAL 보정 대상 회차의 시즌을 찾을 수 없습니다"
                ));
        List<RoutineExecution> executions = operationsRepository
                .findRoutineExecutionsBySeasonRoundIdWithSharedLock(round.getId());
        Instant occurredAt = clock.instant();
        int appendedCount = outboxPort.appendIfChanged(snapshotFactory.fromRound(
                UUID.randomUUID(),
                occurredAt,
                season,
                round
        )) ? 1 : 0;
        for (RoutineExecution execution : executions) {
            var snapshot = snapshotFactory.fromExecution(
                    UUID.randomUUID(),
                    occurredAt,
                    round,
                    execution
            );
            if (snapshot.isPresent() && outboxPort.appendIfChanged(snapshot.get())) {
                appendedCount++;
            }
        }
        return appendedCount;
    }

}
