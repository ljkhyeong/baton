package com.personal.baton.application.identity.port.in;

import com.personal.baton.domain.identity.MemberIdentityRole;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public interface MemberIdentityUseCase {

    MemberIdentityResult bindMember(
            UUID teamId,
            UUID memberId,
            AuthenticatedAccount authenticatedAccount
    );

    Optional<MemberIdentityResult> findActiveMember(
            UUID teamId,
            AuthenticatedAccount authenticatedAccount
    );

    Optional<MemberIdentityResult> findActiveMemberForMutation(
            UUID teamId,
            AuthenticatedAccount authenticatedAccount
    );

    record AuthenticatedAccount(UUID accountId) {

        public AuthenticatedAccount {
            Objects.requireNonNull(accountId, "인증 사용자 계정 식별자는 필수입니다");
        }
    }

    record MemberIdentityResult(
            UUID accountId,
            UUID teamId,
            UUID memberId,
            Instant boundAt,
            MemberIdentityRole role
    ) {
    }
}
