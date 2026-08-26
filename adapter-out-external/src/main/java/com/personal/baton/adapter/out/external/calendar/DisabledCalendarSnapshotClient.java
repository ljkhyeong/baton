package com.personal.baton.adapter.out.external.calendar;

import com.personal.baton.application.calendar.CalendarSnapshot;
import com.personal.baton.application.calendar.port.out.CalendarSnapshotClient;

public final class DisabledCalendarSnapshotClient implements CalendarSnapshotClient {

    @Override
    public DeliveryResult deliver(CalendarSnapshot snapshot) {
        return DeliveryResult.retryable("CAL_DELIVERY_DISABLED");
    }
}
