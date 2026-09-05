package com.personal.baton.application.workspace.port.in;

import java.util.UUID;

public interface NotificationPreferencesUseCase {
    PreferencesResult get(UUID accountId);
    PreferencesResult configure(UUID accountId, ConfigurePreferencesCommand command);
    record ConfigurePreferencesCommand(long expectedVersion, boolean deadlineSoonEnabled, boolean overdueEnabled,
            boolean handoffEnabled, int deadlineLeadHours) {}
    record PreferencesResult(UUID accountId, long version, boolean deadlineSoonEnabled, boolean overdueEnabled,
            boolean handoffEnabled, int deadlineLeadHours) {}
}
