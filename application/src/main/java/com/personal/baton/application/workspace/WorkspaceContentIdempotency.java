package com.personal.baton.application.workspace;

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
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

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
        ContentCreationIdempotency reservation = attempt.reservation();
        if (reservation == null) {
            throw new IllegalStateException("새 콘텐츠 생성 요청에 멱등 예약이 없습니다");
        }
        repository.saveContentCreationIdempotency(reservation);
    }

    IllegalStateException missingResource(ContentCreationOperation operation) {
        return new IllegalStateException(
                "멱등 생성된 " + operation.name() + " 리소스를 찾을 수 없습니다"
        );
    }

    String fingerprintRoleRequest(UUID teamId, UUID seasonId, Role role) {
        MessageDigest digest = contentRequestDigest(ContentCreationOperation.ROLE, teamId, seasonId);
        updateDigest(digest, role.getName());
        updateDigest(digest, role.getPurpose());
        updateNullableDigest(digest, role.getCurrentMemberId());
        updateNullableDigest(digest, role.getNextMemberId());
        updateNullableDigest(digest, role.getAssignmentStartDate());
        updateNullableDigest(digest, role.getAssignmentEndDate());
        updateDigest(digest, Integer.toString(role.getResponsibilities().size()));
        for (String responsibility : role.getResponsibilities()) {
            updateDigest(digest, responsibility);
        }
        updateNullableDigest(digest, role.getRisk());
        return HexFormat.of().formatHex(digest.digest());
    }

    String fingerprintNextSeasonRequest(
            UUID teamId,
            UUID sourceSeasonId,
            Season targetSeason,
            List<UUID> roleIds,
            List<UUID> routineIds
    ) {
        MessageDigest digest = contentRequestDigest(
                ContentCreationOperation.SEASON,
                teamId,
                sourceSeasonId
        );
        updateDigest(digest, targetSeason.getName());
        updateDigest(digest, targetSeason.getStartDate().toString());
        updateDigest(digest, targetSeason.getEndDate().toString());
        updateDigest(digest, Integer.toString(roleIds.size()));
        for (UUID roleId : roleIds) {
            updateDigest(digest, roleId.toString());
        }
        updateDigest(digest, Integer.toString(routineIds.size()));
        for (UUID routineId : routineIds) {
            updateDigest(digest, routineId.toString());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    String fingerprintMemberRequest(UUID teamId, UUID seasonId, Member member) {
        MessageDigest digest = contentRequestDigest(
                ContentCreationOperation.MEMBER,
                teamId,
                seasonId
        );
        updateDigest(digest, member.getName());
        return HexFormat.of().formatHex(digest.digest());
    }

    String fingerprintRoutineRequest(UUID teamId, UUID seasonId, Routine routine) {
        MessageDigest digest = contentRequestDigest(
                ContentCreationOperation.ROUTINE,
                teamId,
                seasonId
        );
        updateDigest(digest, routine.getTitle());
        updateDigest(digest, routine.getPhase().name());
        updateDigest(digest, routine.getDueLabel());
        updateDigest(digest, routine.getOwnerRoleId().toString());
        updateDigest(digest, routine.getDetail());
        if (routine.getDeadlineDayOffset() != null) {
            updateNullableDigest(digest, routine.getDeadlineDayOffset());
            updateNullableDigest(digest, routine.getDeadlineTime());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    String fingerprintSeasonRoundRequest(UUID teamId, UUID seasonId, SeasonRound round) {
        MessageDigest digest = contentRequestDigest(
                ContentCreationOperation.ROUND,
                teamId,
                seasonId
        );
        updateDigest(digest, round.getName());
        updateDigest(digest, round.getMeetingDate().toString());
        return HexFormat.of().formatHex(digest.digest());
    }

    String fingerprintDecisionRequest(UUID teamId, UUID seasonId, Decision decision) {
        MessageDigest digest = contentRequestDigest(
                ContentCreationOperation.DECISION,
                teamId,
                seasonId
        );
        updateDigest(digest, decision.getTitle());
        updateDigest(digest, decision.getReason());
        updateDigest(digest, decision.getAlternative());
        updateDigest(digest, decision.getAuthorMemberId().toString());
        updateDigest(digest, Integer.toString(decision.getRoleIds().size()));
        for (UUID roleId : decision.getRoleIds()) {
            updateDigest(digest, roleId.toString());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    String fingerprintHandoffItemRequest(
            UUID teamId,
            UUID seasonId,
            HandoffItem item
    ) {
        MessageDigest digest = contentRequestDigest(
                ContentCreationOperation.HANDOFF_ITEM,
                teamId,
                seasonId
        );
        updateDigest(digest, item.getRoleId().toString());
        updateDigest(digest, item.getLabel());
        updateDigest(digest, item.getCategory().name());
        return HexFormat.of().formatHex(digest.digest());
    }

    String fingerprintRoleResourceRequest(
            UUID teamId,
            UUID seasonId,
            RoleResource resource
    ) {
        MessageDigest digest = contentRequestDigest(
                ContentCreationOperation.ROLE_RESOURCE,
                teamId,
                seasonId
        );
        updateDigest(digest, resource.getRoleId().toString());
        updateDigest(digest, resource.getTitle());
        updateDigest(digest, resource.getUrl());
        updateNullableDigest(digest, resource.getDescription());
        return HexFormat.of().formatHex(digest.digest());
    }

    String fingerprintRoleHandoffRequest(
            UUID teamId,
            UUID seasonId,
            UUID roleId,
            PrepareRoleHandoffCommand command
    ) {
        MessageDigest digest = contentRequestDigest(
                ContentCreationOperation.ROLE_HANDOFF,
                teamId,
                seasonId
        );
        updateDigest(digest, roleId.toString());
        updateDigest(digest, command.toMemberId().toString());
        updateDigest(digest, command.incomingAssignmentStartDate().toString());
        updateNullableDigest(digest, command.incomingAssignmentEndDate());
        return HexFormat.of().formatHex(digest.digest());
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
        return HexFormat.of().formatHex(hashDomainValues(
                CONTENT_IDEMPOTENCY_HASH_DOMAIN + ":" + operation.name(),
                List.of(teamId.toString(), seasonId.toString(), idempotencyKey)
        ));
    }

    private MessageDigest contentRequestDigest(
            ContentCreationOperation operation,
            UUID teamId,
            UUID seasonId
    ) {
        MessageDigest digest = newSha256Digest();
        updateDigest(digest, CONTENT_REQUEST_FINGERPRINT_DOMAIN + ":" + operation.name());
        updateDigest(digest, teamId.toString());
        updateDigest(digest, seasonId.toString());
        return digest;
    }

    private void updateNullableDigest(MessageDigest digest, Object value) {
        if (value == null) {
            digest.update((byte) 0);
            return;
        }
        digest.update((byte) 1);
        updateDigest(digest, value.toString());
    }

    private byte[] hashDomainValues(String domain, List<String> values) {
        MessageDigest digest = newSha256Digest();
        updateDigest(digest, domain);
        for (String value : values) {
            updateDigest(digest, value);
        }
        return digest.digest();
    }

    private void updateDigest(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private MessageDigest newSha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다", exception);
        }
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
