package com.personal.baton.adapter.out.external.calendar;

import com.personal.baton.application.calendar.CalendarSeasonMetadata;
import com.personal.baton.application.calendar.CalendarSnapshot;
import com.personal.baton.application.calendar.port.out.CalendarSeasonMetadataClient;
import com.personal.baton.application.calendar.port.out.CalendarSnapshotClient;

public final class DisabledCalendarClient implements CalendarSnapshotClient, CalendarSeasonMetadataClient {

    @Override
    public DeliveryResult deliver(CalendarSnapshot snapshot) {
        return DeliveryResult.retryable("CAL_DELIVERY_DISABLED");
    }

    @Override
    public DeliveryResult deliver(CalendarSeasonMetadata metadata) {
        return DeliveryResult.retryable("CAL_DELIVERY_DISABLED");
    }
}
