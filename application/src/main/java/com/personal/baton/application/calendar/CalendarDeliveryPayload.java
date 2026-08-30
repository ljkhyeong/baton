package com.personal.baton.application.calendar;

public sealed interface CalendarDeliveryPayload permits CalendarSnapshot, CalendarSeasonMetadata {

    int revision();
}
