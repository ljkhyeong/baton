package com.personal.baton.adapter.in.web.workspace;

import com.personal.baton.application.workspace.port.in.NotificationPreferencesUseCase.PreferencesResult;
import java.util.UUID;

public record NotificationPreferencesResponse(UUID accountId, long version, boolean deadlineSoonEnabled, boolean overdueEnabled,
        boolean handoffEnabled, int deadlineLeadHours) {
    static NotificationPreferencesResponse from(PreferencesResult value) {
        return new NotificationPreferencesResponse(value.accountId(), value.version(), value.deadlineSoonEnabled(),
                value.overdueEnabled(), value.handoffEnabled(), value.deadlineLeadHours());
    }
}
