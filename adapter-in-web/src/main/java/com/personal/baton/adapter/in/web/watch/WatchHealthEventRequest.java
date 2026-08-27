package com.personal.baton.adapter.in.web.watch;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.personal.baton.application.watch.WatchResourceHealth;
import com.personal.baton.application.watch.error.WatchHealthEventChangedAtOutOfRangeException;
import com.personal.baton.application.watch.port.in.AcceptWatchHealthEventUseCase.AcceptWatchHealthEventCommand;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;

public record WatchHealthEventRequest(
        @NotNull(message = "필수입니다")
        UUID eventId,

        @NotBlank(message = "필수입니다")
        @Pattern(
                regexp = "RESOURCE_HEALTH_CHANGED",
                message = "지원하지 않는 이벤트 유형입니다"
        )
        String eventType,

        @NotBlank(message = "필수입니다")
        @Pattern(
                regexp = "[A-Za-z0-9][A-Za-z0-9._:-]{0,127}",
                message = "형식이 올바르지 않습니다"
        )
        String resourceReference,

        @NotNull(message = "필수입니다")
        @PositiveOrZero(message = "0 이상이어야 합니다")
        Long sourceRevision,

        UUID attemptId,

        @NotNull(message = "필수입니다")
        WatchResourceHealth previousHealth,

        @NotNull(message = "필수입니다")
        WatchResourceHealth currentHealth,

        @NotBlank(message = "필수입니다")
        String changedAt
) {

    @JsonIgnore
    @AssertTrue(message = "previousHealth와 currentHealth는 달라야 합니다")
    public boolean isHealthChanged() {
        return previousHealth == null
                || currentHealth == null
                || previousHealth != currentHealth;
    }

    AcceptWatchHealthEventCommand toCommand() {
        Instant parsedChangedAt;
        try {
            parsedChangedAt = Instant.parse(changedAt);
        } catch (DateTimeParseException exception) {
            throw new WatchHealthEventChangedAtOutOfRangeException();
        }
        return new AcceptWatchHealthEventCommand(
                eventId,
                eventType,
                resourceReference,
                sourceRevision,
                attemptId,
                previousHealth,
                currentHealth,
                parsedChangedAt
        );
    }

    @JsonAnySetter
    public void rejectUnknownField(String fieldName, Object ignoredValue) {
        throw new IllegalArgumentException("지원하지 않는 WATCH 이벤트 필드입니다: " + fieldName);
    }
}
