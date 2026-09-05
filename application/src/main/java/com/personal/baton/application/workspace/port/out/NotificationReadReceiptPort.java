package com.personal.baton.application.workspace.port.out;

import java.time.Instant;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;

public interface NotificationReadReceiptPort {
    Set<UUID> findRead(UUID accountId, Collection<UUID> notificationIds);
    void markRead(UUID accountId, UUID notificationId, Instant readAt);
}
