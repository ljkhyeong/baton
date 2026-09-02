package com.personal.baton.adapter.out.external.calendar;

import com.personal.baton.application.calendar.CalendarRecoveryManifest;

public record CalendarRecoverySeasonManifestRequest(
        int itemCount,
        String itemDigest,
        int metadataRevision,
        String metadataDigest
) {
    static CalendarRecoverySeasonManifestRequest from(CalendarRecoveryManifest.Season season) {
        return new CalendarRecoverySeasonManifestRequest(
                season.itemCount(),
                season.itemDigest(),
                season.metadataRevision(),
                season.metadataDigest()
        );
    }
}
