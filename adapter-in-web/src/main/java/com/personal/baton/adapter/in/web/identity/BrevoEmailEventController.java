package com.personal.baton.adapter.in.web.identity;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.personal.baton.application.identity.EmailDeliveryEvent;
import com.personal.baton.application.identity.port.in.ReceiveEmailDeliveryReceiptUseCase;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BrevoEmailEventController {

    public static final String PATH = "/api/v1/integrations/brevo/email-events";
    private static final String DELIVERY_PREFIX = "baton-delivery-id:";
    private final ReceiveEmailDeliveryReceiptUseCase useCase;

    public BrevoEmailEventController(ReceiveEmailDeliveryReceiptUseCase useCase) {
        this.useCase = useCase;
    }

    @PostMapping(PATH)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void receive(@Valid @RequestBody EmailEventRequest request) {
        if (request.correlation() == null) {
            return;
        }
        EmailDeliveryEvent event;
        try {
            event = EmailDeliveryEvent.valueOf(request.event().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return;
        }
        useCase.receive(Long.parseLong(request.correlation().substring(DELIVERY_PREFIX.length())),
                event, Instant.ofEpochSecond(request.timestamp()));
    }

    public record EmailEventRequest(
            @NotBlank(message = "이벤트 종류가 필요합니다")
            @Size(max = 64, message = "이벤트 종류는 64자 이하여야 합니다") String event,
            @JsonProperty("X-Mailin-custom")
            @Pattern(regexp = "baton-delivery-id:[1-9][0-9]{0,17}",
                    message = "발송 ID 형식이 올바르지 않습니다") String correlation,
            @JsonProperty("ts_event") @NotNull(message = "이벤트 시각이 필요합니다")
            @Min(value = 1, message = "이벤트 시각 범위를 확인해 주세요")
            @DecimalMax(value = "253402300799", message = "이벤트 시각 범위를 확인해 주세요") Long timestamp
    ) {}
}
