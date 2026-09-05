package com.personal.baton.application.workspace.port.out;

import com.personal.baton.domain.workspace.NotificationPreferences;
import java.util.Optional;
import java.util.UUID;

public interface NotificationPreferencesRepository {
    Optional<NotificationPreferences> find(UUID accountId);
    NotificationPreferences save(NotificationPreferences preferences);
}
