package com.personal.baton.application.calendar;

import com.personal.baton.domain.workspace.RoutineExecution;
import com.personal.baton.domain.workspace.Season;
import com.personal.baton.domain.workspace.SeasonRound;
import java.util.List;

public interface CalendarChangeRecorder {

    void record(Season season, SeasonRound round, List<RoutineExecution> executions);

    void recordSeason(Season season);
}
