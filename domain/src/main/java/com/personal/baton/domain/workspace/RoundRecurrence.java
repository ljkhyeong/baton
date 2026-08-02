package com.personal.baton.domain.workspace;

import java.time.LocalDate;
import java.util.Objects;

public enum RoundRecurrence {
    WEEKLY(7),
    BIWEEKLY(14);

    private final int intervalDays;

    RoundRecurrence(int intervalDays) {
        this.intervalDays = intervalDays;
    }

    public int getIntervalDays() {
        return intervalDays;
    }

    public LocalDate next(LocalDate occurrenceDate) {
        return Objects.requireNonNull(occurrenceDate, "회차 예정일은 필수입니다")
                .plusDays(intervalDays);
    }
}
