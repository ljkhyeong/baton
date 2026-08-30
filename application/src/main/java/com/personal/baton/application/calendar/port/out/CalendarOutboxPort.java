package com.personal.baton.application.calendar.port.out;

import com.personal.baton.application.calendar.CalendarSnapshotDraft;
import com.personal.baton.application.calendar.CalendarDelivery;
import com.personal.baton.application.calendar.CalendarDeliveryPayload;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface CalendarOutboxPort {

    int append(CalendarSnapshotDraft snapshot);

    boolean appendIfChanged(CalendarSnapshotDraft snapshot);

    boolean appendSeasonMetadataIfChanged(UUID seasonId, String displayName, Instant occurredAt);

    List<CalendarDelivery> claimPending(
            int batchSize,
            Instant claimedAt,
            Duration leaseDuration,
            boolean seasonMetadata
    );

    boolean markDelivered(
            CalendarDeliveryPayload payload,
            UUID leaseToken,
            Instant deliveredAt,
            String resultCode
    );

    boolean markRetry(
            CalendarDeliveryPayload payload,
            UUID leaseToken,
            Instant availableAt,
            String errorCode
    );

    boolean markFailed(
            CalendarDeliveryPayload payload,
            UUID leaseToken,
            Instant failedAt,
            String errorCode
    );

}
