package com.personal.baton.application.brief;

import java.time.Instant;
import java.util.UUID;

public record BriefEditionDeliveryStatus(UUID editionId, Status status, Instant checkedAt) {
    public enum Status {
        ADDITIONAL_DELIVERIES,
        NO_ADDITIONAL_DELIVERIES,
        UNKNOWN
    }
}
