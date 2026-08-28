package com.personal.baton.application.calendar.port.out;

import com.personal.baton.application.calendar.CalendarBackfillCandidate;
import java.util.List;
import java.util.UUID;

public interface CalendarBackfillPort {

    List<CalendarBackfillCandidate> findCandidates(UUID afterRoundId, int limit);
}
