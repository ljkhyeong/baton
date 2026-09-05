package com.personal.baton.application.workspace;

import com.personal.baton.application.roundauth.port.out.RoundAuthorizationRepository;
import com.personal.baton.application.workspace.error.WorkspaceAccessDeniedException;
import com.personal.baton.application.workspace.error.WorkspaceNotFoundException;
import com.personal.baton.application.workspace.port.in.WorkspaceNotificationUseCase;
import com.personal.baton.application.workspace.port.out.NotificationReadReceiptPort;
import com.personal.baton.application.workspace.port.out.WorkspacePeopleRepository;
import com.personal.baton.domain.workspace.Member;
import com.personal.baton.domain.workspace.RoleHandoffStatus;
import com.personal.baton.domain.workspace.RoutineStatus;
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
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class WorkspaceNotificationService implements WorkspaceNotificationUseCase {
    private final WorkspaceScopeAuthorizer authorizer;
    private final WorkspaceProjectionReader reader;
    private final RoundAuthorizationRepository memberships;
    private final WorkspacePeopleRepository people;
    private final NotificationReadReceiptPort receipts;
    private final Clock clock;
    private final NotificationPreferencesRepository preferences;

    public WorkspaceNotificationService(WorkspaceScopeAuthorizer authorizer, WorkspaceProjectionReader reader,
            RoundAuthorizationRepository memberships, WorkspacePeopleRepository people,
            NotificationReadReceiptPort receipts, Clock clock, NotificationPreferencesRepository preferences) {
        this.preferences = preferences;
        this.authorizer = authorizer;
        this.reader = reader;
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
        var workspace = reader.read(scope);
        Instant now = clock.instant();
        var settings = preferences.find(accountId).orElseGet(() -> NotificationPreferences.defaults(accountId));
        Set<UUID> roles = workspace.roles().stream().filter(role -> member.getId().equals(role.currentMemberId()))
                .map(role -> role.id()).collect(Collectors.toSet());
        List<NotificationResult> pending = new ArrayList<>();
        for (var round : workspace.rounds()) {
            if (round.archivedAt() != null) continue;
            for (var execution : round.routineExecutions()) {
                Instant deadline = execution.deadlineAt();
                if (execution.status() == RoutineStatus.DONE || !roles.contains(execution.ownerRoleId())
                        || deadline == null || deadline.isAfter(now.plus(Duration.ofHours(settings.getDeadlineLeadHours())))) continue;
                WorkspaceNotificationKind kind = !now.isBefore(deadline)
                        ? WorkspaceNotificationKind.OVERDUE : WorkspaceNotificationKind.DEADLINE_SOON;
                if (!settings.includes(kind)) continue;
                pending.add(notification(accountId, teamId, seasonId, kind, execution.id(), execution.ownerRoleId(),
                        round.id(), execution.title(), deadline));
            }
        }
        for (var handoff : workspace.roleHandoffs()) {
            if (!settings.isHandoffEnabled() || handoff.status() != RoleHandoffStatus.TRANSFERRED || !member.getId().equals(handoff.toMemberId())) continue;
            String roleName = workspace.roles().stream().filter(role -> role.id().equals(handoff.roleId()))
                    .map(role -> role.name()).findFirst().orElse("역할 인수인계");
            pending.add(notification(accountId, teamId, seasonId, WorkspaceNotificationKind.HANDOFF_REQUEST,
                    handoff.id(), handoff.roleId(), null, roleName, handoff.transferredAt()));
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
