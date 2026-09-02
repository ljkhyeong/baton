package com.personal.baton.application.calendar;

import com.personal.baton.application.calendar.port.in.CompleteCalendarRecoveryUseCase;
import com.personal.baton.application.calendar.port.out.CalendarRecoveryClient;
import com.personal.baton.application.calendar.port.out.CalendarRecoveryStatePort;
import com.personal.baton.application.calendar.port.out.CalendarSnapshotClient.DeliveryResult;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class CalendarRecoveryCompletionService implements CompleteCalendarRecoveryUseCase {

    private final CalendarRecoveryStatePort statePort;
    private final CalendarRecoveryClient client;

    public CalendarRecoveryCompletionService(
            CalendarRecoveryStatePort statePort,
            CalendarRecoveryClient client
    ) {
        this.statePort = statePort;
        this.client = client;
    }

    @Override
    public Result complete(UUID recoveryId) {
        var state = statePort.loadReadyState();
        if (state.isEmpty()) {
            return Result.waiting("CAL_OUTBOX_NOT_READY");
        }
        CalendarRecoveryManifest manifest = CalendarRecoveryManifest.from(state.get());
        for (var season : manifest.seasons()) {
            Result failure = failure(client.verifySeason(recoveryId, season));
            if (failure != null) {
                return failure;
            }
        }
        Result failure = failure(client.complete(recoveryId, manifest));
        return failure == null ? Result.completed(manifest.seasons().size()) : failure;
    }

    private Result failure(DeliveryResult delivery) {
        return switch (delivery.outcome()) {
            case DELIVERED -> null;
            case RETRYABLE_FAILURE -> Result.waiting(delivery.code());
            case PERMANENT_FAILURE -> Result.failed(delivery.code());
        };
    }
}
