package com.personal.baton.adapter.in.web.watch;

import com.personal.baton.application.watch.port.in.AcceptWatchHealthEventUseCase.WatchHealthEventReceipt;
import java.time.Instant;
import java.util.UUID;

public record WatchHealthEventReceiptResponse(
        UUID eventId,
        Instant acceptedAt
) {

    static WatchHealthEventReceiptResponse from(WatchHealthEventReceipt receipt) {
        return new WatchHealthEventReceiptResponse(receipt.eventId(), receipt.acceptedAt());
    }
}
