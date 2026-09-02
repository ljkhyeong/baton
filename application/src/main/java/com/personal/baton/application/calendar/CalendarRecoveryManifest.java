package com.personal.baton.application.calendar;

import com.personal.baton.application.calendar.port.out.CalendarRecoveryStatePort.RecoveryState;
import java.util.List;
import java.util.UUID;

public record CalendarRecoveryManifest(
        List<Season> seasons,
        String seasonDigest
) {
    public CalendarRecoveryManifest {
        seasons = List.copyOf(seasons);
    }

    public static CalendarRecoveryManifest from(RecoveryState state) {
        List<Season> seasons = state.seasons().stream()
                .map(source -> new Season(
                        source.seasonId(),
                        source.snapshots().size(),
                        CalendarRecoveryManifestDigest.items(source.snapshots()),
                        source.metadata().revision(),
                        CalendarRecoveryManifestDigest.metadata(source.metadata())
                ))
                .toList();
        return new CalendarRecoveryManifest(
                seasons,
                CalendarRecoveryManifestDigest.seasons(seasons)
        );
    }

    public record Season(
            UUID seasonId,
            int itemCount,
            String itemDigest,
            int metadataRevision,
            String metadataDigest
    ) {
    }
}
