package com.personal.baton.application.calendar;

import com.personal.baton.application.calendar.port.in.BackfillCalendarSnapshotsUseCase;
import com.personal.baton.application.calendar.port.out.CalendarBackfillPort;
import java.util.List;
import java.util.UUID;
import java.util.function.ToIntFunction;
import org.springframework.stereotype.Service;

@Service
public class CalendarSnapshotBackfillService implements BackfillCalendarSnapshotsUseCase {

    static final int PAGE_SIZE = 100;

    private final CalendarBackfillPort backfillPort;
    private final CalendarSnapshotBackfillWorker worker;

    public CalendarSnapshotBackfillService(
            CalendarBackfillPort backfillPort,
            CalendarSnapshotBackfillWorker worker
    ) {
        this.backfillPort = backfillPort;
        this.worker = worker;
    }

    @Override
    public BackfillResult backfill() {
        scanCandidates(candidate -> {
            worker.verifyTextCompatibility(candidate);
            return 0;
        });
        ScanResult result = scanCandidates(worker::backfill);
        return new BackfillResult(result.candidateCount(), result.appendedCount());
    }

    private ScanResult scanCandidates(ToIntFunction<CalendarBackfillCandidate> operation) {
        UUID afterRoundId = null;
        int candidateCount = 0;
        int appendedCount = 0;

        while (true) {
            List<CalendarBackfillCandidate> candidates = backfillPort.findCandidates(
                    afterRoundId,
                    PAGE_SIZE
            );
            if (candidates.isEmpty()) {
                break;
            }
            candidateCount += candidates.size();
            for (CalendarBackfillCandidate candidate : candidates) {
                appendedCount += operation.applyAsInt(candidate);
            }
            afterRoundId = candidates.getLast().roundId();
            if (candidates.size() < PAGE_SIZE) {
                break;
            }
        }
        return new ScanResult(candidateCount, appendedCount);
    }

    private record ScanResult(int candidateCount, int appendedCount) {
    }
}
