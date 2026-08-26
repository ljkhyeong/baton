package com.personal.baton.application.calendar.port.out;

import com.personal.baton.application.calendar.CalendarSnapshotDraft;
import com.personal.baton.application.calendar.CalendarSnapshotDelivery;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface CalendarOutboxPort {

    int append(CalendarSnapshotDraft snapshot);

    List<CalendarSnapshotDelivery> claimPending(
            int batchSize,
            Instant claimedAt,
            Duration leaseDuration
    );

    boolean markDelivered(
            int revision,
            UUID leaseToken,
            Instant deliveredAt,
            String resultCode
    );

    boolean markRetry(
            int revision,
            UUID leaseToken,
            Instant availableAt,
            String errorCode
    );

    boolean markFailed(
            int revision,
            UUID leaseToken,
            Instant failedAt,
            String errorCode
    );
}
