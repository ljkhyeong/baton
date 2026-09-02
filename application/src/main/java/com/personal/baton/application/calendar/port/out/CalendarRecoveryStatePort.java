package com.personal.baton.application.calendar.port.out;

import com.personal.baton.application.calendar.CalendarSeasonMetadata;
import com.personal.baton.application.calendar.CalendarSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CalendarRecoveryStatePort {

    int requeueLatestSnapshots(Instant availableAt);

    Optional<RecoveryState> loadReadyState();

    record RecoveryState(List<SeasonState> seasons) {
        public RecoveryState {
            seasons = List.copyOf(seasons);
        }
    }

    record SeasonState(
            UUID seasonId,
            List<CalendarSnapshot> snapshots,
            CalendarSeasonMetadata metadata
    ) {
        public SeasonState {
            snapshots = List.copyOf(snapshots);
        }
    }
}
