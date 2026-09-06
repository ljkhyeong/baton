package com.personal.baton.application.holiday;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record PublicHolidayCalendar(int year, Status status, Instant checkedAt, List<Holiday> holidays) {
    public enum Status { READY, UNAVAILABLE, DISABLED, OUT_OF_RANGE }
    public record Holiday(LocalDate date, String name) {}

    public static PublicHolidayCalendar empty(int year, Status status) {
        return new PublicHolidayCalendar(year, status, null, List.of());
    }
}
