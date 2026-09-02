package com.personal.baton.adapter.out.external.calendar;

import com.personal.baton.application.calendar.CalendarRecoveryManifest;

public record CalendarRecoveryCompletionRequest(
        int seasonCount,
        String seasonDigest
) {
    static CalendarRecoveryCompletionRequest from(CalendarRecoveryManifest manifest) {
        return new CalendarRecoveryCompletionRequest(
                manifest.seasons().size(),
                manifest.seasonDigest()
        );
    }
}
