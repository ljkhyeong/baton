package com.personal.baton.policy.identity;

import com.personal.baton.domain.identity.MemberIdentityBinding;
import com.personal.baton.domain.identity.MemberIdentityRole;
import com.personal.baton.domain.identity.UserAccount;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

@Tag("policy")
class UserIdentityPolicyTest {

    private static final UUID ACCOUNT_ID =
            UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID TEAM_ID =
            UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID MEMBER_ID =
            UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final Instant NOW = Instant.parse("2026-07-30T12:00:00Z");

    @DisplayName("사용자 계정은 로그인 공급자와 무관한 불변 식별자와 생성 시각을 보존한다")
    @Test
    void preservesProviderIndependentAccountIdentity() {
        UserAccount account = UserAccount.create(ACCOUNT_ID, NOW);

        assertThat(account.getId()).isEqualTo(ACCOUNT_ID);
        assertThat(account.getCreatedAt()).isEqualTo(NOW);
        assertThatNullPointerException()
                .isThrownBy(() -> UserAccount.create(null, NOW));
        assertThatNullPointerException()
                .isThrownBy(() -> UserAccount.create(ACCOUNT_ID, null));
    }

    @DisplayName("구성원 결속은 팀과 사용자 계정 및 최초 결속 시각을 함께 고정한다")
    @Test
    void preservesImmutableMemberIdentityBinding() {
        MemberIdentityBinding binding = MemberIdentityBinding.bind(
                MEMBER_ID,
                TEAM_ID,
                ACCOUNT_ID,
                NOW
        );

        assertThat(binding.getMemberId()).isEqualTo(MEMBER_ID);
        assertThat(binding.getTeamId()).isEqualTo(TEAM_ID);
        assertThat(binding.getUserAccountId()).isEqualTo(ACCOUNT_ID);
        assertThat(binding.getBoundAt()).isEqualTo(NOW);
        assertThat(binding.getRole()).isEqualTo(MemberIdentityRole.MEMBER);
        assertThat(binding.belongsTo(ACCOUNT_ID)).isTrue();
        assertThat(binding.belongsTo(UUID.randomUUID())).isFalse();
    }
}
