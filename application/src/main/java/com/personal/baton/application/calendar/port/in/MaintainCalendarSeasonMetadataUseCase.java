package com.personal.baton.application.calendar.port.in;

public interface MaintainCalendarSeasonMetadataUseCase {

    Result maintain(Mode mode);

    enum Mode { OFF, BACKFILL, REPLAY }

    record Result(int candidateCount, int appendedCount, int requeuedCount) {
    }
}
