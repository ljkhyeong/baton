package com.personal.baton.application.calendar.port.out;

import com.personal.baton.application.calendar.CalendarSnapshotDraft;

public interface CalendarOutboxPort {

    int append(CalendarSnapshotDraft snapshot);
}
