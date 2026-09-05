package com.personal.baton.adapter.in.web.workspace;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.UUID;

public record ResourceReviewScheduleRequest(@NotNull UUID expectedAccountId, @NotNull @Min(-1) Long expectedVersion,
        @Min(1) @Max(365) Integer intervalDays, LocalDate nextReviewOn) {}
