package com.personal.baton.policy.identity;

import com.personal.baton.domain.identity.MemberIdentityBinding;
import com.personal.baton.domain.identity.MemberIdentityRole;
import com.personal.baton.domain.identity.OidcExternalIdentity;
import com.personal.baton.domain.identity.OwnerBootstrapInvitation;
import com.personal.baton.domain.identity.OwnerBootstrapInvitation.Acceptance;
import com.personal.baton.domain.identity.OwnerBootstrapInvitationStateException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("policy")
class OwnerBootstrapInvitationPolicyTest {

    private static final Instant NOW = Instant.parse("2026-07-30T12:00:00Z");
    private static final UUID INVITATION_ID =
            UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID TEAM_ID =
            UUID.fromString("10000000-0000-4000-8000-000000000002");
    private static final UUID MEMBER_ID =
            UUID.fromString("10000000-0000-4000-8000-000000000003");
    private static final UUID ACCOUNT_ID =
            UUID.fromString("10000000-0000-4000-8000-000000000004");
    private static final String FIRST_HASH = "a".repeat(64);
    private static final String SECOND_HASH = "b".repeat(64);

    @DisplayName("OIDC 외부 신원은 검증된 issuer와 subject 및 내부 계정 연결을 변경 없이 보존한다")
    @Test
    void preservesOidcExternalIdentityLink() {
        OidcExternalIdentity identity = OidcExternalIdentity.link(
                UUID.randomUUID(),
                FIRST_HASH,
                "https://issuer.example.com",
                "provider-subject",
                ACCOUNT_ID,
                NOW
        );

        assertThat(identity.represents(
                "https://issuer.example.com",
                "provider-subject"
        )).isTrue();
        assertThat(identity.represents(
                "https://issuer.example.com",
                "other-subject"
        )).isFalse();
        assertThat(identity.getUserAccountId()).isEqualTo(ACCOUNT_ID);
        assertThat(identity.getLinkedAt()).isEqualTo(NOW);
    }

    @DisplayName("OWNER 결속은 기존 MEMBER 결속을 승격해도 최초 결속 시각을 유지한다")
    @Test
    void promotesMemberBindingWithoutChangingBoundAt() {
        MemberIdentityBinding binding = MemberIdentityBinding.bind(
                MEMBER_ID,
                TEAM_ID,
                ACCOUNT_ID,
                NOW.minusSeconds(60)
        );

        binding.grantOwner();

        assertThat(binding.getRole()).isEqualTo(MemberIdentityRole.OWNER);
        assertThat(binding.isOwner()).isTrue();
        assertThat(binding.getBoundAt()).isEqualTo(NOW.minusSeconds(60));
    }

    @DisplayName("bootstrap 초대는 만료 전 한 계정만 소비하고 같은 계정 재시도만 허용한다")
    @Test
    void consumesOnceAndReplaysOnlyForSameAccount() {
        OwnerBootstrapInvitation invitation = invitation(NOW.plusSeconds(3600));

        assertThat(invitation.consume(ACCOUNT_ID, NOW)).isEqualTo(Acceptance.CONSUMED);
        assertThat(invitation.consume(ACCOUNT_ID, NOW.plusSeconds(1)))
                .isEqualTo(Acceptance.REPLAY);
        assertThatThrownBy(() -> invitation.consume(
                UUID.randomUUID(),
                NOW.plusSeconds(1)
        )).isInstanceOfSatisfying(
                OwnerBootstrapInvitationStateException.class,
                exception -> assertThat(exception.getReason())
                        .isEqualTo(OwnerBootstrapInvitationStateException.Reason.USED)
        );
    }

    @DisplayName("bootstrap 초대는 만료 경계 시각부터 소비할 수 없다")
    @Test
    void rejectsConsumptionAtExpiryBoundary() {
        OwnerBootstrapInvitation invitation = invitation(NOW.plusSeconds(3600));

        assertThatThrownBy(() -> invitation.consume(
                ACCOUNT_ID,
                NOW.plusSeconds(3600)
        )).isInstanceOfSatisfying(
                OwnerBootstrapInvitationStateException.class,
                exception -> assertThat(exception.getReason())
                        .isEqualTo(OwnerBootstrapInvitationStateException.Reason.EXPIRED)
        );
    }

    @DisplayName("폐기한 bootstrap 초대는 만료 전에도 소비할 수 없다")
    @Test
    void rejectsRevokedInvitation() {
        OwnerBootstrapInvitation invitation = invitation(NOW.plusSeconds(3600));
        invitation.revoke(NOW.plusSeconds(10));

        assertThatThrownBy(() -> invitation.consume(
                ACCOUNT_ID,
                NOW.plusSeconds(20)
        )).isInstanceOfSatisfying(
                OwnerBootstrapInvitationStateException.class,
                exception -> assertThat(exception.getReason())
                        .isEqualTo(OwnerBootstrapInvitationStateException.Reason.REVOKED)
        );
    }

    private OwnerBootstrapInvitation invitation(Instant expiresAt) {
        return OwnerBootstrapInvitation.issue(
                INVITATION_ID,
                TEAM_ID,
                MEMBER_ID,
                FIRST_HASH,
                SECOND_HASH,
                NOW,
                expiresAt
        );
    }
}
