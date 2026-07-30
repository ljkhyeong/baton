package com.personal.baton.application.identity.port.out;

import com.personal.baton.domain.identity.MemberInvitation;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public interface MemberInvitationRepository {

    Optional<MemberInvitation> findByIdempotencyKeyHash(String idempotencyKeyHash);

    InvitationInsertResult insertIfAbsent(MemberInvitation invitation);

    Optional<MemberInvitation> findByTokenHash(String tokenHash);

    Optional<InvitationObservation> findObservationByTokenHash(String tokenHash);

    Optional<MemberInvitation> findByTokenHashForUpdate(String tokenHash);

    Optional<MemberInvitation> findByTeamIdAndIdForUpdate(UUID teamId, UUID invitationId);

    Optional<MemberInvitation> findOpenByTeamIdAndMemberIdForUpdate(
            UUID teamId,
            UUID memberId,
            Instant now
    );

    List<MemberInvitation> findAllOpenByTeamId(UUID teamId, Instant now);

    MemberInvitation save(MemberInvitation invitation);

    record InvitationInsertResult(MemberInvitation invitation, boolean created) {
    }

    record InvitationObservation(
            UUID invitationId,
            UUID teamId,
            UUID memberId,
            UUID issuedByAccountId,
            Instant expiresAt,
            Instant revokedAt,
            Instant consumedAt
    ) {

        public InvitationObservation {
            Objects.requireNonNull(invitationId, "구성원 초대 식별자는 필수입니다");
            Objects.requireNonNull(teamId, "구성원 초대 팀은 필수입니다");
            Objects.requireNonNull(memberId, "구성원 초대 대상은 필수입니다");
            Objects.requireNonNull(issuedByAccountId, "구성원 초대 발급 계정은 필수입니다");
            Objects.requireNonNull(expiresAt, "구성원 초대 만료 시각은 필수입니다");
        }

        public boolean isOpenAt(Instant now) {
            Objects.requireNonNull(now, "구성원 초대 확인 시각은 필수입니다");
            return consumedAt == null && revokedAt == null && now.isBefore(expiresAt);
        }
    }
}
