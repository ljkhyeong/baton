package com.personal.baton.application.calendar;

import com.personal.baton.application.calendar.port.out.CalendarOutboxPort;
import com.personal.baton.domain.workspace.RoutineExecution;
import com.personal.baton.domain.workspace.Season;
import com.personal.baton.domain.workspace.SeasonRound;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class CalendarSnapshotRecorder implements CalendarChangeRecorder {

    private final CalendarOutboxPort outboxPort;
    private final CalendarSnapshotFactory snapshotFactory;
    private final CalendarCaptureState captureState;
    private final Clock clock;

    public CalendarSnapshotRecorder(
            CalendarOutboxPort outboxPort,
            CalendarCaptureState captureState,
            Clock clock
    ) {
        this.outboxPort = outboxPort;
        this.snapshotFactory = new CalendarSnapshotFactory();
        this.captureState = captureState;
        this.clock = clock;
    }

    @Override
    public void record(Season season, SeasonRound round, List<RoutineExecution> executions) {
        if (!captureState.enabled()) {
            return;
        }
        Instant occurredAt = Instant.now(clock);
        outboxPort.appendIfChanged(snapshotFactory.fromRound(UUID.randomUUID(), occurredAt, season, round));
        for (RoutineExecution execution : executions) {
            snapshotFactory.fromExecution(UUID.randomUUID(), occurredAt, round, execution)
                    .ifPresent(outboxPort::appendIfChanged);
        }
    }
}
