package com.personal.baton.application.workspace.port.in;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

public interface WorkspaceDecisionUseCase {

    DecisionResult createDecisionAuthorized(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            WorkspaceAuthorization authorization,
            CreateDecisionCommand command
    );

    @Transactional
    default DecisionResult createDecision(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            String accessKey,
            CreateDecisionCommand command
    ) {
        return createDecisionAuthorized(
                teamId,
                seasonId,
                idempotencyKey,
                legacy(accessKey),
                command
        );
    }

    DecisionResult updateDecisionAuthorized(
            UUID teamId,
            UUID seasonId,
            UUID decisionId,
            WorkspaceAuthorization authorization,
            UpdateDecisionCommand command
    );

    @Transactional
    default DecisionResult updateDecision(
            UUID teamId,
            UUID seasonId,
            UUID decisionId,
            String accessKey,
            UpdateDecisionCommand command
    ) {
        return updateDecisionAuthorized(
                teamId,
                seasonId,
                decisionId,
                legacy(accessKey),
                command
        );
    }

    DecisionResult updateDecisionArchiveAuthorized(
            UUID teamId,
            UUID seasonId,
            UUID decisionId,
            WorkspaceAuthorization authorization,
            boolean archived
    );

    @Transactional
    default DecisionResult updateDecisionArchive(
            UUID teamId,
            UUID seasonId,
            UUID decisionId,
            String accessKey,
            boolean archived
    ) {
        return updateDecisionArchiveAuthorized(
                teamId,
                seasonId,
                decisionId,
                legacy(accessKey),
                archived
        );
    }

    private static WorkspaceAuthorization legacy(String accessKey) {
        return new WorkspaceAuthorization.LegacyAccessKey(accessKey);
    }

    record CreateDecisionCommand(
            String title,
            String reason,
            String alternative,
            UUID authorMemberId,
            List<UUID> roleIds
    ) {
    }

    record UpdateDecisionCommand(
            String title,
            String reason,
            String alternative,
            UUID authorMemberId,
            List<UUID> roleIds
    ) {
    }

    record DecisionResult(
            UUID id,
            String title,
            String reason,
            String alternative,
            Instant createdAt,
            UUID authorMemberId,
            String authorName,
            List<UUID> roleIds,
            Instant archivedAt
    ) {
    }
}
