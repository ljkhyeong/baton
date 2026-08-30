package com.personal.baton.application.calendar;

import com.personal.baton.application.calendar.port.in.MaintainCalendarSeasonMetadataUseCase;
import com.personal.baton.application.calendar.port.out.CalendarBackfillPort;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Service;

@Service
public class CalendarSeasonMetadataMaintenanceService implements MaintainCalendarSeasonMetadataUseCase {

    static final int PAGE_SIZE = 100;

    private final CalendarBackfillPort candidates;
    private final CalendarSeasonMetadataMaintenanceWorker worker;

    public CalendarSeasonMetadataMaintenanceService(CalendarBackfillPort candidates, CalendarSeasonMetadataMaintenanceWorker worker) {
        this.candidates = candidates;
        this.worker = worker;
    }

    @Override
    public Result maintain(Mode mode) {
        if (mode == Mode.OFF) {
            return new Result(0, 0, 0);
        }
        scan(worker::verifyTextCompatibility);
        return scan(candidate -> worker.maintain(candidate, mode));
    }

    private Result scan(Function<CalendarSeasonBackfillCandidate, Result> operation) {
        UUID afterSeasonId = null;
        int candidateCount = 0;
        int appendedCount = 0;
        int requeuedCount = 0;
        while (true) {
            var page = candidates.findSeasonCandidates(afterSeasonId, PAGE_SIZE);
            for (var candidate : page) {
                Result result = operation.apply(candidate);
                candidateCount += result.candidateCount();
                appendedCount += result.appendedCount();
                requeuedCount += result.requeuedCount();
            }
            if (page.size() < PAGE_SIZE) {
                return new Result(candidateCount, appendedCount, requeuedCount);
            }
            afterSeasonId = page.getLast().seasonId();
        }
    }
}
