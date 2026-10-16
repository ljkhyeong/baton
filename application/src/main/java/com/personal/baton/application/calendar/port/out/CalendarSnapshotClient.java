package com.personal.baton.application.calendar.port.out;

import com.personal.baton.application.calendar.CalendarSnapshot;
import com.personal.baton.application.delivery.DeliveryResult;

public interface CalendarSnapshotClient {

    DeliveryResult deliver(CalendarSnapshot snapshot);
}
