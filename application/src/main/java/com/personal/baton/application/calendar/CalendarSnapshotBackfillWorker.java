package com.personal.baton.application.calendar;

import com.personal.baton.application.calendar.port.out.CalendarOutboxPort;
import com.personal.baton.application.workspace.port.out.WorkspaceRepository;
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

    private final WorkspaceRepository workspaceRepository;
    private final CalendarOutboxPort outboxPort;
    private final CalendarSnapshotFactory snapshotFactory;
    private final Clock clock;

    public CalendarSnapshotBackfillWorker(
            WorkspaceRepository workspaceRepository,
            CalendarOutboxPort outboxPort,
            Clock clock
    ) {
        this.workspaceRepository = workspaceRepository;
        this.outboxPort = outboxPort;
        this.snapshotFactory = new CalendarSnapshotFactory();
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int backfill(CalendarBackfillCandidate candidate) {
        SeasonRound round = workspaceRepository
                .findSeasonRoundBySeasonIdAndIdForUpdate(
                        candidate.seasonId(),
                        candidate.roundId()
                )
                .orElse(null);
        if (round == null) {
            return 0;
        }
        Season season = workspaceRepository.findSeasonById(candidate.seasonId())
                .orElseThrow(() -> new IllegalStateException(
                        "CAL 보정 대상 회차의 시즌을 찾을 수 없습니다"
                ));
        List<RoutineExecution> executions = workspaceRepository
                .findRoutineExecutionsBySeasonRoundIdWithSharedLock(round.getId());
        Instant occurredAt = clock.instant();
        int appendedCount = outboxPort.appendIfChanged(snapshotFactory.fromRound(
                UUID.randomUUID(),
                occurredAt,
                season,
                round
        )) ? 1 : 0;
        for (RoutineExecution execution : executions) {
            boolean appended = snapshotFactory.fromExecution(
                            UUID.randomUUID(),
                            occurredAt,
                            round,
                            execution
                    )
                    .map(outboxPort::appendIfChanged)
                    .orElse(false);
            if (appended) {
                appendedCount++;
            }
        }
        return appendedCount;
    }
}
