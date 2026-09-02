package com.personal.baton.adapter.out.external.calendar;

import com.personal.baton.application.calendar.CalendarSeasonMetadata;
import com.personal.baton.application.calendar.CalendarSnapshot;
import com.personal.baton.application.calendar.CalendarRecoveryManifest;
import com.personal.baton.application.calendar.port.out.CalendarRecoveryClient;
import com.personal.baton.application.calendar.port.out.CalendarSeasonMetadataClient;
import com.personal.baton.application.calendar.port.out.CalendarSnapshotClient;
import java.util.UUID;

public final class DisabledCalendarClient implements
        CalendarSnapshotClient,
        CalendarSeasonMetadataClient,
        CalendarRecoveryClient {

    @Override
    public DeliveryResult deliver(CalendarSnapshot snapshot) {
        return DeliveryResult.retryable("CAL_DELIVERY_DISABLED");
    }

    @Override
    public DeliveryResult deliver(CalendarSeasonMetadata metadata) {
        return DeliveryResult.retryable("CAL_DELIVERY_DISABLED");
    }

    @Override
    public DeliveryResult verifySeason(UUID recoveryId, CalendarRecoveryManifest.Season season) {
        return DeliveryResult.retryable("CAL_DELIVERY_DISABLED");
    }

    @Override
    public DeliveryResult complete(UUID recoveryId, CalendarRecoveryManifest manifest) {
        return DeliveryResult.retryable("CAL_DELIVERY_DISABLED");
    }
}
