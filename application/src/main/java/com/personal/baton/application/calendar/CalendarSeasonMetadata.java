package com.personal.baton.application.calendar;

import java.util.UUID;

public record CalendarSeasonMetadata(UUID seasonId, int revision, String displayName)
        implements CalendarDeliveryPayload {
}
