package com.personal.baton.application.calendar;

import java.util.UUID;

public record CalendarDelivery(CalendarDeliveryPayload payload, int attemptCount, UUID leaseToken) {
}
