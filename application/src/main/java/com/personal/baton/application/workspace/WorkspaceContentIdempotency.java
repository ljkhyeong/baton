package com.personal.baton.application.workspace;

import org.springframework.stereotype.Component;
import com.personal.baton.application.crypto.DomainSeparatedSha256;
import com.personal.baton.application.workspace.error.IdempotencyKeyReusedException;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.PrepareRoleHandoffCommand;
import com.personal.baton.application.workspace.port.out.WorkspaceRepository;
import com.personal.baton.domain.workspace.ContentCreationIdempotency;
import com.personal.baton.domain.workspace.ContentCreationOperation;
import com.personal.baton.domain.workspace.Decision;
import com.personal.baton.domain.workspace.HandoffItem;
import com.personal.baton.domain.workspace.Member;
import com.personal.baton.domain.workspace.Role;
import com.personal.baton.domain.workspace.RoleResource;
import com.personal.baton.domain.workspace.Routine;
import com.personal.baton.domain.workspace.Season;
import com.personal.baton.domain.workspace.SeasonRound;
import java.util.List;
import java.util.UUID;

@Component
final class WorkspaceContentIdempotency {

    private static final String CONTENT_IDEMPOTENCY_HASH_DOMAIN =
            "baton:workspace-content-idempotency:v1";
    private static final String CONTENT_REQUEST_FINGERPRINT_DOMAIN =
            "baton:workspace-content-request:v1";

    private final WorkspaceRepository repository;

    WorkspaceContentIdempotency(WorkspaceRepository repository) {
        this.repository = repository;
    }

    ContentCreationAttempt prepare(
            UUID teamId,
            UUID seasonId,
            ContentCreationOperation operation,
            String idempotencyKey,
            String requestFingerprint,
            UUID resourceId
    ) {
        String idempotencyHash = contentIdempotencyHash(
                teamId,
                seasonId,
                operation,
                idempotencyKey
        );
        ContentCreationIdempotency existing = repository.findContentCreationIdempotency(
                teamId,
                idempotencyHash
        ).orElse(null);
        if (existing != null) {
            validateReplay(existing, teamId, seasonId, operation, requestFingerprint);
            return ContentCreationAttempt.replay(existing.getResourceId());
        }
        return ContentCreationAttempt.create(ContentCreationIdempotency.create(
                UUID.randomUUID(),
                teamId,
                seasonId,
                operation,
                idempotencyHash,
                requestFingerprint,
                resourceId
        ));
    }

    void reserve(ContentCreationAttempt attempt) {
        repository.saveContentCreationIdempotency(attempt.reservation());
    }

    IllegalStateException missingResource(ContentCreationOperation operation) {
        return new IllegalStateException(
                "멱등 생성된 " + operation.name() + " 리소스를 찾을 수 없습니다"
        );
    }

    String fingerprintRoleRequest(UUID teamId, UUID seasonId, Role role) {
        DomainSeparatedSha256 digest = contentRequestDigest(
                ContentCreationOperation.ROLE,
                teamId,
                seasonId
        );
        digest.append(role.getName());
        digest.append(role.getPurpose());
        digest.appendNullable(role.getCurrentMemberId());
        digest.appendNullable(role.getNextMemberId());
        digest.appendNullable(role.getAssignmentStartDate());
        digest.appendNullable(role.getAssignmentEndDate());
        digest.append(Integer.toString(role.getResponsibilities().size()));
        for (String responsibility : role.getResponsibilities()) {
            digest.append(responsibility);
        }
        digest.appendNullable(role.getRisk());
        return digest.digestHex();
    }

    String fingerprintNextSeasonRequest(
            UUID teamId,
            UUID sourceSeasonId,
            Season targetSeason,
            List<UUID> roleIds,
            List<UUID> routineIds
    ) {
        DomainSeparatedSha256 digest = contentRequestDigest(
                ContentCreationOperation.SEASON,
                teamId,
                sourceSeasonId
        );
        digest.append(targetSeason.getName());
        digest.append(targetSeason.getStartDate().toString());
        digest.append(targetSeason.getEndDate().toString());
        digest.append(Integer.toString(roleIds.size()));
        for (UUID roleId : roleIds) {
            digest.append(roleId.toString());
        }
        digest.append(Integer.toString(routineIds.size()));
        for (UUID routineId : routineIds) {
            digest.append(routineId.toString());
        }
        return digest.digestHex();
    }

    String fingerprintMemberRequest(UUID teamId, UUID seasonId, Member member) {
        DomainSeparatedSha256 digest = contentRequestDigest(
                ContentCreationOperation.MEMBER,
                teamId,
                seasonId
        );
        digest.append(member.getName());
        return digest.digestHex();
    }

    String fingerprintRoutineRequest(UUID teamId, UUID seasonId, Routine routine) {
        DomainSeparatedSha256 digest = contentRequestDigest(
                ContentCreationOperation.ROUTINE,
                teamId,
                seasonId
        );
        digest.append(routine.getTitle());
        digest.append(routine.getPhase().name());
        digest.append(routine.getDueLabel());
        digest.append(routine.getOwnerRoleId().toString());
        digest.append(routine.getDetail());
        if (routine.getDeadlineDayOffset() != null) {
            digest.appendNullable(routine.getDeadlineDayOffset());
            digest.appendNullable(routine.getDeadlineTime());
        }
        return digest.digestHex();
    }

    String fingerprintSeasonRoundRequest(UUID teamId, UUID seasonId, SeasonRound round) {
        DomainSeparatedSha256 digest = contentRequestDigest(
                ContentCreationOperation.ROUND,
                teamId,
                seasonId
        );
        digest.append(round.getName());
        digest.append(round.getMeetingDate().toString());
        return digest.digestHex();
    }

    String fingerprintDecisionRequest(UUID teamId, UUID seasonId, Decision decision) {
        DomainSeparatedSha256 digest = contentRequestDigest(
                ContentCreationOperation.DECISION,
                teamId,
                seasonId
        );
        digest.append(decision.getTitle());
        digest.append(decision.getReason());
        digest.append(decision.getAlternative());
        digest.append(decision.getAuthorMemberId().toString());
        digest.append(Integer.toString(decision.getRoleIds().size()));
        for (UUID roleId : decision.getRoleIds()) {
            digest.append(roleId.toString());
        }
        return digest.digestHex();
    }

    String fingerprintHandoffItemRequest(
            UUID teamId,
            UUID seasonId,
            HandoffItem item
    ) {
        DomainSeparatedSha256 digest = contentRequestDigest(
                ContentCreationOperation.HANDOFF_ITEM,
                teamId,
                seasonId
        );
        digest.append(item.getRoleId().toString());
        digest.append(item.getLabel());
        digest.append(item.getCategory().name());
        return digest.digestHex();
    }

    String fingerprintRoleResourceRequest(
            UUID teamId,
            UUID seasonId,
            RoleResource resource
    ) {
        DomainSeparatedSha256 digest = contentRequestDigest(
                ContentCreationOperation.ROLE_RESOURCE,
                teamId,
                seasonId
        );
        digest.append(resource.getRoleId().toString());
        digest.append(resource.getTitle());
        digest.append(resource.getUrl());
        digest.appendNullable(resource.getDescription());
        return digest.digestHex();
    }

    String fingerprintRoleHandoffRequest(
            UUID teamId,
            UUID seasonId,
            UUID roleId,
            PrepareRoleHandoffCommand command
    ) {
        DomainSeparatedSha256 digest = contentRequestDigest(
                ContentCreationOperation.ROLE_HANDOFF,
                teamId,
                seasonId
        );
        digest.append(roleId.toString());
        digest.append(command.toMemberId().toString());
        digest.append(command.incomingAssignmentStartDate().toString());
        digest.appendNullable(command.incomingAssignmentEndDate());
        return digest.digestHex();
    }

    private void validateReplay(
            ContentCreationIdempotency existing,
            UUID teamId,
            UUID seasonId,
            ContentCreationOperation operation,
            String requestFingerprint
    ) {
        if (!existing.getTeamId().equals(teamId)
                || !existing.getSeasonId().equals(seasonId)
                || existing.getOperation() != operation) {
            throw new IllegalStateException(
                    "저장된 콘텐츠 생성 멱등 범위가 요청과 일치하지 않습니다"
            );
        }
        if (!existing.getRequestFingerprint().equals(requestFingerprint)) {
            throw new IdempotencyKeyReusedException();
        }
    }

    private String contentIdempotencyHash(
            UUID teamId,
            UUID seasonId,
            ContentCreationOperation operation,
            String idempotencyKey
    ) {
        return DomainSeparatedSha256.hashHex(
                CONTENT_IDEMPOTENCY_HASH_DOMAIN + ":" + operation.name(),
                List.of(teamId.toString(), seasonId.toString(), idempotencyKey)
        );
    }

    private DomainSeparatedSha256 contentRequestDigest(
            ContentCreationOperation operation,
            UUID teamId,
            UUID seasonId
    ) {
        return DomainSeparatedSha256
                .inDomain(CONTENT_REQUEST_FINGERPRINT_DOMAIN + ":" + operation.name())
                .append(teamId.toString())
                .append(seasonId.toString());
    }

    record ContentCreationAttempt(
            ContentCreationIdempotency reservation,
            UUID replayResourceId
    ) {

        private static ContentCreationAttempt create(ContentCreationIdempotency reservation) {
            return new ContentCreationAttempt(reservation, null);
        }

        private static ContentCreationAttempt replay(UUID resourceId) {
            return new ContentCreationAttempt(null, resourceId);
        }
    }
}
