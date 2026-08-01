package com.personal.baton.application.workspace.port.in;

import com.personal.baton.application.workspace.port.in.WorkspaceRoleUseCase.RoleResult;
import com.personal.baton.domain.workspace.RoleHandoffStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

public interface WorkspaceRoleHandoffUseCase {

    RoleHandoffTransitionResult prepareRoleHandoffAuthorized(
            UUID teamId,
            UUID seasonId,
            UUID roleId,
            String idempotencyKey,
            WorkspaceAuthorization authorization,
            PrepareRoleHandoffCommand command
    );

    @Transactional
    default RoleHandoffTransitionResult prepareRoleHandoff(
            UUID teamId,
            UUID seasonId,
            UUID roleId,
            String idempotencyKey,
            String accessKey,
            PrepareRoleHandoffCommand command
    ) {
        return prepareRoleHandoffAuthorized(
                teamId,
                seasonId,
                roleId,
                idempotencyKey,
                legacy(accessKey),
                command
        );
    }

    RoleHandoffTransitionResult transferRoleHandoffAuthorized(
            UUID teamId,
            UUID seasonId,
            UUID roleId,
            UUID handoffId,
            WorkspaceAuthorization authorization,
            TransferRoleHandoffCommand command
    );

    @Transactional
    default RoleHandoffTransitionResult transferRoleHandoff(
            UUID teamId,
            UUID seasonId,
            UUID roleId,
            UUID handoffId,
            String accessKey,
            TransferRoleHandoffCommand command
    ) {
        return transferRoleHandoffAuthorized(
                teamId,
                seasonId,
                roleId,
                handoffId,
                legacy(accessKey),
                command
        );
    }

    RoleHandoffTransitionResult acceptRoleHandoffAuthorized(
            UUID teamId,
            UUID seasonId,
            UUID roleId,
            UUID handoffId,
            WorkspaceAuthorization authorization,
            ConfirmRoleHandoffCommand command
    );

    @Transactional
    default RoleHandoffTransitionResult acceptRoleHandoff(
            UUID teamId,
            UUID seasonId,
            UUID roleId,
            UUID handoffId,
            String accessKey,
            ConfirmRoleHandoffCommand command
    ) {
        return acceptRoleHandoffAuthorized(
                teamId,
                seasonId,
                roleId,
                handoffId,
                legacy(accessKey),
                command
        );
    }

    RoleHandoffTransitionResult cancelRoleHandoffAuthorized(
            UUID teamId,
            UUID seasonId,
            UUID roleId,
            UUID handoffId,
            WorkspaceAuthorization authorization,
            ConfirmRoleHandoffCommand command
    );

    @Transactional
    default RoleHandoffTransitionResult cancelRoleHandoff(
            UUID teamId,
            UUID seasonId,
            UUID roleId,
            UUID handoffId,
            String accessKey,
            ConfirmRoleHandoffCommand command
    ) {
        return cancelRoleHandoffAuthorized(
                teamId,
                seasonId,
                roleId,
                handoffId,
                legacy(accessKey),
                command
        );
    }

    private static WorkspaceAuthorization legacy(String accessKey) {
        return new WorkspaceAuthorization.LegacyAccessKey(accessKey);
    }

    record PrepareRoleHandoffCommand(
            UUID toMemberId,
            LocalDate incomingAssignmentStartDate,
            LocalDate incomingAssignmentEndDate
    ) {
    }

    record TransferRoleHandoffCommand(
            UUID confirmedByMemberId,
            boolean warningAcknowledged
    ) {
    }

    record ConfirmRoleHandoffCommand(UUID confirmedByMemberId) {
    }

    record RoleHandoffTransitionResult(
            RoleResult role,
            RoleHandoffResult handoff
    ) {
    }

    record RoleHandoffResult(
            UUID id,
            UUID roleId,
            UUID fromMemberId,
            UUID toMemberId,
            LocalDate outgoingAssignmentStartDate,
            LocalDate outgoingAssignmentEndDate,
            LocalDate incomingAssignmentStartDate,
            LocalDate incomingAssignmentEndDate,
            RoleHandoffStatus status,
            Instant preparedAt,
            Instant transferredAt,
            Instant acceptedAt,
            Instant cancelledAt,
            UUID transferredByMemberId,
            UUID acceptedByMemberId,
            UUID cancelledByMemberId,
            Integer activeItemCount,
            Integer incompleteItemCount,
            Integer resourceCount,
            boolean warningAcknowledged
    ) {
    }
}
