package com.personal.baton.adapter.out.external.calendar;

import com.personal.baton.application.calendar.CalendarSeasonMetadata;

record CalendarSeasonMetadataRequest(int revision, String displayName) {

    static CalendarSeasonMetadataRequest from(CalendarSeasonMetadata metadata) {
        return new CalendarSeasonMetadataRequest(metadata.revision(), metadata.displayName());
    }
}
