package com.personal.baton.application.relay;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public record RelayOutboxEvent(
        int contractVersion,
        UUID eventId,
        String eventType,
        int eventVersion,
        String subjectReference,
        Instant occurredAt
) {

    public static final int CURRENT_CONTRACT_VERSION = 1;

    private static final Pattern EVENT_TYPE_PATTERN = Pattern.compile(
            "^[A-Z][A-Z0-9._-]{0,99}$"
    );
    private static final Pattern SUBJECT_REFERENCE_PATTERN = Pattern.compile(
            "^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$"
    );

    public RelayOutboxEvent {
        if (contractVersion != CURRENT_CONTRACT_VERSION) {
            throw new IllegalArgumentException("RELAY contractVersion은 1이어야 합니다");
        }
        Objects.requireNonNull(eventId, "RELAY eventId는 필수입니다");
        eventType = requireText(eventType, "eventType", EVENT_TYPE_PATTERN);
        if (eventVersion < 1) {
            throw new IllegalArgumentException("RELAY eventVersion은 1 이상이어야 합니다");
        }
        subjectReference = requireText(
                subjectReference,
                "subjectReference",
                SUBJECT_REFERENCE_PATTERN
        );
        occurredAt = Objects.requireNonNull(
                occurredAt,
                "RELAY occurredAt은 필수입니다"
        ).truncatedTo(ChronoUnit.MICROS);
    }

    private static String requireText(String value, String name, Pattern pattern) {
        String requiredValue = Objects.requireNonNull(value, "RELAY " + name + "은 필수입니다");
        if (!pattern.matcher(requiredValue).matches()) {
            throw new IllegalArgumentException("RELAY " + name + " 형식이 올바르지 않습니다");
        }
        return requiredValue;
    }
}
