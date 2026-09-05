package com.personal.baton.adapter.in.web.workspace;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record NotificationPreferencesRequest(@NotNull UUID expectedAccountId, @NotNull @Min(-1) Long expectedVersion,
        @NotNull Boolean deadlineSoonEnabled, @NotNull Boolean overdueEnabled, @NotNull Boolean handoffEnabled,
        @NotNull @Min(1) @Max(168) Integer deadlineLeadHours) {}
