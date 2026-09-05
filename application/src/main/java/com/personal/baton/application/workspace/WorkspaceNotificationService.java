package com.personal.baton.application.workspace;

import com.personal.baton.application.roundauth.port.out.RoundAuthorizationRepository;
import com.personal.baton.application.workspace.error.WorkspaceAccessDeniedException;
import com.personal.baton.application.workspace.error.WorkspaceNotFoundException;
import com.personal.baton.application.workspace.port.in.WorkspaceNotificationUseCase;
import com.personal.baton.application.workspace.port.out.NotificationReadReceiptPort;
import com.personal.baton.application.workspace.port.out.WorkspacePeopleRepository;
import com.personal.baton.application.workspace.port.out.WorkspaceOperationsRepository;
import com.personal.baton.domain.workspace.Member;
import com.personal.baton.domain.workspace.WorkspaceNotificationKind;
import java.nio.charset.StandardCharsets;
import com.personal.baton.domain.workspace.NotificationPreferences;
import com.personal.baton.application.workspace.port.out.NotificationPreferencesRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class WorkspaceNotificationService implements WorkspaceNotificationUseCase {
    private final WorkspaceScopeAuthorizer authorizer;
    private final WorkspaceOperationsRepository operations;
    private final RoundAuthorizationRepository memberships;
    private final WorkspacePeopleRepository people;
    private final NotificationReadReceiptPort receipts;
    private final Clock clock;
    private final NotificationPreferencesRepository preferences;

    public WorkspaceNotificationService(WorkspaceScopeAuthorizer authorizer, WorkspaceOperationsRepository operations,
            RoundAuthorizationRepository memberships, WorkspacePeopleRepository people,
            NotificationReadReceiptPort receipts, Clock clock, NotificationPreferencesRepository preferences) {
        this.preferences = preferences;
        this.authorizer = authorizer;
        this.operations = operations;
        this.memberships = memberships;
        this.people = people;
        this.receipts = receipts;
        this.clock = clock;
    }

    @Override
    public NotificationInboxResult getInbox(UUID teamId, UUID seasonId, String accessKey, UUID accountId) {
        WorkspaceScope scope = authorizer.authorizeRead(teamId, seasonId, accessKey);
        Member member = memberships.findMembership(accountId, teamId)
                .flatMap(value -> people.findMemberById(value.getMemberId()))
                .filter(value -> value.getTeamId().equals(teamId)).filter(Member::isActive)
                .orElseThrow(WorkspaceAccessDeniedException::new);
        if (scope.season().isEnded()) return new NotificationInboxResult(accountId, teamId, seasonId, List.of());
        Instant now = clock.instant();
        var settings = preferences.find(accountId).orElseGet(() -> NotificationPreferences.defaults(accountId));
        List<NotificationResult> pending = new ArrayList<>();
        if (settings.isDeadlineSoonEnabled() || settings.isOverdueEnabled()) {
            Instant deadlineThrough = now.plus(Duration.ofHours(settings.getDeadlineLeadHours()));
            for (var execution : operations.findPendingDeadlineExecutions(teamId, seasonId, member.getId())) {
                Instant deadline = execution.getDeadlineAt();
                if (deadline.isAfter(deadlineThrough)) continue;
                WorkspaceNotificationKind kind = !now.isBefore(deadline)
                        ? WorkspaceNotificationKind.OVERDUE : WorkspaceNotificationKind.DEADLINE_SOON;
                if (!settings.includes(kind)) continue;
                pending.add(notification(accountId, teamId, seasonId, kind, execution.getId(), execution.getOwnerRoleId(),
                        execution.getSeasonRoundId(), execution.getTitle(), deadline));
            }
        }
        if (settings.isHandoffEnabled()) {
            for (var handoff : people.findTransferredHandoffs(teamId, seasonId, member.getId())) {
                pending.add(notification(accountId, teamId, seasonId, WorkspaceNotificationKind.HANDOFF_REQUEST,
                        handoff.getId(), handoff.getRoleId(), null, handoff.getRoleName(), handoff.getTransferredAt()));
            }
        }
        Set<UUID> read = receipts.findRead(accountId, pending.stream().map(NotificationResult::id).toList());
        List<NotificationResult> results = pending.stream()
                .sorted(Comparator.comparing(NotificationResult::occurredAt).thenComparing(NotificationResult::id))
                .map(value -> new NotificationResult(value.id(), value.kind(), value.sourceId(), value.roleId(),
                        value.roundId(), value.title(), value.occurredAt(), read.contains(value.id()))).toList();
        return new NotificationInboxResult(accountId, teamId, seasonId, results);
    }

    @Override
    @Transactional
    public NotificationInboxResult markRead(UUID teamId, UUID seasonId, String accessKey, UUID accountId, UUID notificationId) {
        var inbox = getInbox(teamId, seasonId, accessKey, accountId);
        if (inbox.notifications().stream().noneMatch(value -> value.id().equals(notificationId))) {
            throw new WorkspaceNotFoundException("NOTIFICATION_NOT_FOUND", "현재 확인할 수 있는 알림이 아닙니다");
        }
        receipts.markRead(accountId, notificationId, clock.instant());
        return new NotificationInboxResult(accountId, teamId, seasonId, inbox.notifications().stream()
                .map(value -> new NotificationResult(value.id(), value.kind(), value.sourceId(), value.roleId(),
                        value.roundId(), value.title(), value.occurredAt(), value.read() || value.id().equals(notificationId))).toList());
    }

    private NotificationResult notification(UUID accountId, UUID teamId, UUID seasonId, WorkspaceNotificationKind kind,
            UUID sourceId, UUID roleId, UUID roundId, String title, Instant occurredAt) {
        String identity = "baton:notification:v1:" + accountId + ":" + teamId + ":" + seasonId + ":" + kind
                + ":" + sourceId + ":" + occurredAt;
        return new NotificationResult(UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)), kind,
                sourceId, roleId, roundId, title, occurredAt, false);
    }
}
