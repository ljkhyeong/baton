package com.personal.baton.application.calendar.port.out;

import com.personal.baton.application.calendar.CalendarSeasonMetadata;
import com.personal.baton.application.calendar.port.out.CalendarSnapshotClient.DeliveryResult;

public interface CalendarSeasonMetadataClient {

    DeliveryResult deliver(CalendarSeasonMetadata metadata);
}
