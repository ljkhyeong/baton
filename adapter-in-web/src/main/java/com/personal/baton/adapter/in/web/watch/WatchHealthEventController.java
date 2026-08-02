package com.personal.baton.adapter.in.web.watch;

import com.personal.baton.application.watch.port.in.AcceptWatchHealthEventUseCase;
import com.personal.baton.application.watch.port.in.AcceptWatchHealthEventUseCase.WatchHealthEventReceipt;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(WatchHealthEventController.PATH)
public class WatchHealthEventController {

    public static final String PATH = "/api/v1/internal/resource-health-events";

    private final AcceptWatchHealthEventUseCase useCase;

    public WatchHealthEventController(AcceptWatchHealthEventUseCase useCase) {
        this.useCase = useCase;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public WatchHealthEventReceiptResponse accept(
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @Valid @RequestBody WatchHealthEventRequest request
    ) {
        WatchHealthEventReceipt receipt = useCase.accept(idempotencyKey, request.toCommand());
        return WatchHealthEventReceiptResponse.from(receipt);
    }
}
