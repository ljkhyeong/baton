package com.personal.baton.application.calendar;

import java.util.UUID;

public record CalendarBackfillCandidate(UUID roundId, UUID seasonId) {
}
