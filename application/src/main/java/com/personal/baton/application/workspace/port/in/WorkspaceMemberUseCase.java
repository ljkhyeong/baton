package com.personal.baton.application.workspace.port.in;

import java.time.Instant;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

public interface WorkspaceMemberUseCase {

    MemberResult createMemberAuthorized(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            WorkspaceAuthorization authorization,
            CreateMemberCommand command
    );

    @Transactional
    default MemberResult createMember(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            String accessKey,
            CreateMemberCommand command
    ) {
        return createMemberAuthorized(
                teamId,
                seasonId,
                idempotencyKey,
                legacy(accessKey),
                command
        );
    }

    MemberResult updateMemberAuthorized(
            UUID teamId,
            UUID seasonId,
            UUID memberId,
            WorkspaceAuthorization authorization,
            UpdateMemberCommand command
    );

    @Transactional
    default MemberResult updateMember(
            UUID teamId,
            UUID seasonId,
            UUID memberId,
            String accessKey,
            UpdateMemberCommand command
    ) {
        return updateMemberAuthorized(
                teamId,
                seasonId,
                memberId,
                legacy(accessKey),
                command
        );
    }

    MemberResult updateMemberDeactivationAuthorized(
            UUID teamId,
            UUID seasonId,
            UUID memberId,
            WorkspaceAuthorization authorization,
            boolean deactivated
    );

    @Transactional
    default MemberResult updateMemberDeactivation(
            UUID teamId,
            UUID seasonId,
            UUID memberId,
            String accessKey,
            boolean deactivated
    ) {
        return updateMemberDeactivationAuthorized(
                teamId,
                seasonId,
                memberId,
                legacy(accessKey),
                deactivated
        );
    }

    private static WorkspaceAuthorization legacy(String accessKey) {
        return new WorkspaceAuthorization.LegacyAccessKey(accessKey);
    }

    record CreateMemberCommand(String name) {
    }

    record UpdateMemberCommand(String name) {
    }

    record MemberResult(UUID id, String name, String initials, String tone, Instant deactivatedAt) {
    }
}
