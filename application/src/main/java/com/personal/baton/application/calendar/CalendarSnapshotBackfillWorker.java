package com.personal.baton.application.calendar;

import com.personal.baton.application.calendar.port.out.CalendarOutboxPort;
import com.personal.baton.application.workspace.port.out.WorkspaceRepository;
import com.personal.baton.domain.workspace.RoutineExecution;
import com.personal.baton.domain.workspace.Season;
import com.personal.baton.domain.workspace.SeasonRound;
import java.text.Normalizer;
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

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void verifyTextCompatibility(CalendarBackfillCandidate candidate) {
        SeasonRound round = workspaceRepository.findSeasonRoundById(candidate.roundId())
                .orElse(null);
        if (round == null) {
            return;
        }
        requireCompatibleText(round.getId(), "summary", round.getName());
        List<RoutineExecution> executions = workspaceRepository
                .findRoutineExecutionsBySeasonRoundIds(List.of(round.getId()));
        for (RoutineExecution execution : executions) {
            if (execution.getDeadlineAt() == null) {
                continue;
            }
            requireCompatibleText(execution.getId(), "summary", execution.getTitle());
            requireCompatibleText(
                    execution.getId(),
                    "description",
                    execution.getDetail()
            );
        }
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

    private void requireCompatibleText(UUID sourceItemId, String field, String value) {
        if (!Normalizer.isNormalized(value, Normalizer.Form.NFC)) {
            throw incompatibleText(sourceItemId, field, "NFC 형식이 아닙니다");
        }
        boolean hasForbiddenControl = value.codePoints()
                .anyMatch(codePoint -> Character.isISOControl(codePoint)
                        && codePoint != '\t'
                        && codePoint != '\n');
        if (hasForbiddenControl) {
            throw incompatibleText(sourceItemId, field, "허용되지 않는 제어 문자가 있습니다");
        }
    }

    private IllegalStateException incompatibleText(
            UUID sourceItemId,
            String field,
            String reason
    ) {
        return new IllegalStateException(
                "CAL 보정 대상 " + sourceItemId + "의 " + field + "이(가) " + reason
        );
    }
}
