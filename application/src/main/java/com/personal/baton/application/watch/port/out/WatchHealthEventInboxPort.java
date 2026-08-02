package com.personal.baton.application.watch.port.out;

import com.personal.baton.application.watch.WatchHealthChangedEvent;
import java.time.Instant;

public interface WatchHealthEventInboxPort {

    WatchHealthEventInboxResult accept(
            WatchHealthChangedEvent event,
            Instant acceptedAt
    );

    enum WatchHealthEventInboxStatus {
        ACCEPTED,
        CONFLICT
    }

    record WatchHealthEventInboxResult(
            WatchHealthEventInboxStatus status,
            Instant acceptedAt
    ) {
    }
}
