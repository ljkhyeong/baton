package com.personal.baton.application.workspace;

import org.springframework.stereotype.Component;
import com.personal.baton.domain.workspace.Routine;
import com.personal.baton.domain.workspace.RoutineExecution;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
final class RoutineExecutionSnapshotFactory {

    List<RoutineExecution> snapshotAll(
            UUID roundId,
            List<Routine> routines,
            LocalDate meetingDate,
            ZoneId zoneId
    ) {
        List<RoutineExecution> executions = new ArrayList<>(routines.size());
        for (Routine routine : routines) {
            executions.add(RoutineExecution.snapshot(
                    UUID.randomUUID(),
                    roundId,
                    routine,
                    meetingDate,
                    zoneId
            ));
        }
        return List.copyOf(executions);
    }
}
