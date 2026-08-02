package com.personal.baton.application.watch;

import com.personal.baton.application.watch.error.WatchHealthEventConflictException;
import com.personal.baton.application.watch.error.WatchHealthEventIdMismatchException;
import com.personal.baton.application.watch.error.WatchHealthEventResourceReferenceException;
import com.personal.baton.application.watch.port.in.AcceptWatchHealthEventUseCase;
import com.personal.baton.application.watch.port.out.WatchHealthEventInboxPort;
import com.personal.baton.application.watch.port.out.WatchHealthEventInboxPort.WatchHealthEventInboxResult;
import com.personal.baton.application.watch.port.out.WatchHealthEventInboxPort.WatchHealthEventInboxStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class WatchHealthEventIngestionService implements AcceptWatchHealthEventUseCase {

    private final WatchHealthEventInboxPort inboxPort;
    private final WatchMonitorSource source;
    private final Clock clock;

    public WatchHealthEventIngestionService(
            WatchHealthEventInboxPort inboxPort,
            WatchMonitorSource source,
            Clock clock
    ) {
        this.inboxPort = inboxPort;
        this.source = source;
        this.clock = clock;
    }

    @Override
    public WatchHealthEventReceipt accept(
            UUID idempotencyKey,
            AcceptWatchHealthEventCommand command
    ) {
        Objects.requireNonNull(command, "WATCH 이벤트 수신 command는 필수입니다");
        if (!Objects.equals(idempotencyKey, command.eventId())) {
            throw new WatchHealthEventIdMismatchException();
        }
        UUID resourceId = source.resourceId(command.resourceReference())
                .orElseThrow(WatchHealthEventResourceReferenceException::new);

        WatchHealthChangedEvent event = new WatchHealthChangedEvent(
                command.eventId(),
                command.eventType(),
                resourceId,
                command.resourceReference(),
                command.sourceRevision(),
                command.attemptId(),
                command.previousHealth(),
                command.currentHealth(),
                command.changedAt()
        );
        Instant acceptedAt = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        WatchHealthEventInboxResult result = inboxPort.accept(event, acceptedAt);
        if (result.status() == WatchHealthEventInboxStatus.CONFLICT) {
            throw new WatchHealthEventConflictException();
        }
        return new WatchHealthEventReceipt(event.eventId(), result.acceptedAt());
    }
}
