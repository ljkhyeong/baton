package com.personal.baton.application.calendar;

import com.personal.baton.application.calendar.port.in.MaintainCalendarSeasonMetadataUseCase.Mode;
import com.personal.baton.application.calendar.port.in.MaintainCalendarSeasonMetadataUseCase.Result;
import com.personal.baton.application.calendar.port.out.CalendarOutboxPort;
import com.personal.baton.application.workspace.port.out.WorkspaceRepository;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CalendarSeasonMetadataMaintenanceWorker {

    private final WorkspaceRepository repository;
    private final CalendarOutboxPort outbox;
    private final Clock clock;

    public CalendarSeasonMetadataMaintenanceWorker(WorkspaceRepository repository, CalendarOutboxPort outbox, Clock clock) {
        this.repository = repository;
        this.outbox = outbox;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Result verifyTextCompatibility(CalendarSeasonBackfillCandidate candidate) {
        repository.findSeasonById(candidate.seasonId()).ifPresent(season ->
                CalendarTextCompatibility.require(season.getId(), "displayName", season.getName()));
        return new Result(1, 0, 0);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Result maintain(CalendarSeasonBackfillCandidate candidate, Mode mode) {
        var season = repository.findSeasonByTeamIdAndIdForUpdate(candidate.teamId(), candidate.seasonId()).orElse(null);
        if (season == null) {
            return new Result(1, 0, 0);
        }
        // 사전 점검 이후 변경될 수 있으므로 잠금으로 확정한 현재 이름을 검사한다.
        CalendarTextCompatibility.require(season.getId(), "displayName", season.getName());
        var now = clock.instant();
        boolean appended = outbox.appendSeasonMetadataIfChanged(season.getId(), season.getName(), now);
        boolean requeued = mode == Mode.REPLAY && outbox.requeueLatestSeasonMetadata(season.getId(), now);
        return new Result(1, appended ? 1 : 0, requeued ? 1 : 0);
    }
}
