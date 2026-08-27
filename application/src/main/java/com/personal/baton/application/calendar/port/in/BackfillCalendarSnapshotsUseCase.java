package com.personal.baton.application.calendar.port.in;

public interface BackfillCalendarSnapshotsUseCase {

    BackfillResult backfill();

    record BackfillResult(int candidateCount, int appendedCount) {
    }
}
