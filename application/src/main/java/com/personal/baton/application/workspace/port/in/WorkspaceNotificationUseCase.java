package com.personal.baton.application.workspace.port.in;

import com.personal.baton.domain.workspace.WorkspaceNotificationKind;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface WorkspaceNotificationUseCase {
    NotificationInboxResult getInbox(UUID teamId, UUID seasonId, String accessKey, UUID accountId);
    NotificationInboxResult markRead(UUID teamId, UUID seasonId, String accessKey, UUID accountId, UUID notificationId);

    record NotificationResult(UUID id, WorkspaceNotificationKind kind, UUID sourceId, UUID roleId, UUID roundId,
            String title, Instant occurredAt, boolean read) {}
    record NotificationInboxResult(UUID accountId, UUID teamId, UUID seasonId, List<NotificationResult> notifications) {}
}
