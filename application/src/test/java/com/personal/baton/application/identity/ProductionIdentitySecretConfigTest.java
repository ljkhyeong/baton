package com.personal.baton.application.identity;

import com.personal.baton.bootstrap.config.ProductionIdentitySecretConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("usecase")
class ProductionIdentitySecretConfigTest {

    private static final String CREATION = "production-workspace-creation-key-000000001";
    private static final String RECOVERY = "production-workspace-recovery-key-000000001";
    private static final String BOOTSTRAP = "production-identity-bootstrap-key-000000001";
    private static final String INVITATION = "production-invitation-hmac-secret-000000001";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(context -> context.getEnvironment().setActiveProfiles("production"))
            .withUserConfiguration(ProductionIdentitySecretConfig.class)
            .withPropertyValues(
                    "baton.workspace.creation-key=" + CREATION,
                    "baton.workspace.recovery-key=" + RECOVERY,
                    "baton.identity.bootstrap-invitation-ttl=PT1H",
                    "baton.identity.member-invitation-ttl=PT24H"
            );

    @DisplayName("production 프로필은 운영자 bootstrap 키가 없으면 시작을 거절한다")
    @Test
    void rejectsMissingBootstrapKey() {
        runner.withPropertyValues("baton.identity.invitation-hmac-secret=" + INVITATION)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage(
                            "production 프로필에는 BATON_IDENTITY_BOOTSTRAP_KEY 설정이 필요합니다"
                    );
                });
    }

    @DisplayName("production 프로필은 초대 HMAC 비밀이 없으면 시작을 거절한다")
    @Test
    void rejectsMissingInvitationSecret() {
        runner.withPropertyValues("baton.identity.bootstrap-key=" + BOOTSTRAP)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage(
                            "production 프로필에는 BATON_IDENTITY_INVITATION_HMAC_SECRET 설정이 필요합니다"
                    );
                });
    }

    @DisplayName("production 프로필은 32자보다 짧은 identity 비밀을 거절한다")
    @Test
    void rejectsShortIdentitySecret() {
        runner.withPropertyValues(
                "baton.identity.bootstrap-key=too-short",
                "baton.identity.invitation-hmac-secret=" + INVITATION
        ).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage(
                    "production 프로필의 BATON_IDENTITY_BOOTSTRAP_KEY은(는) 최소 32자여야 합니다"
            );
        });
    }

    @DisplayName("production 프로필은 workspace와 identity 비밀의 재사용을 거절한다")
    @Test
    void rejectsReusedSecret() {
        runner.withPropertyValues(
                "baton.identity.bootstrap-key=" + CREATION,
                "baton.identity.invitation-hmac-secret=" + INVITATION
        ).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage(
                    "production 프로필의 workspace와 identity 비밀값은 모두 서로 달라야 합니다"
            );
        });
    }

    @DisplayName("production 프로필은 1시간이 아닌 bootstrap 초대 수명을 거절한다")
    @Test
    void rejectsNonStandardInvitationTtl() {
        runner.withPropertyValues(
                "baton.identity.bootstrap-key=" + BOOTSTRAP,
                "baton.identity.invitation-hmac-secret=" + INVITATION,
                "baton.identity.bootstrap-invitation-ttl=PT2H"
        ).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage(
                    "production 프로필의 BATON_IDENTITY_BOOTSTRAP_INVITATION_TTL은(는) PT1H여야 합니다"
            );
        });
    }

    @DisplayName("production 프로필은 24시간이 아닌 일반 구성원 초대 수명을 거절한다")
    @Test
    void rejectsNonStandardMemberInvitationTtl() {
        runner.withPropertyValues(
                "baton.identity.bootstrap-key=" + BOOTSTRAP,
                "baton.identity.invitation-hmac-secret=" + INVITATION,
                "baton.identity.member-invitation-ttl=PT48H"
        ).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage(
                    "production 프로필의 BATON_IDENTITY_MEMBER_INVITATION_TTL은(는) PT24H여야 합니다"
            );
        });
    }

    @DisplayName("production 프로필은 독립 생성한 네 비밀을 허용한다")
    @Test
    void acceptsIndependentSecrets() {
        runner.withPropertyValues(
                "baton.identity.bootstrap-key=" + BOOTSTRAP,
                "baton.identity.invitation-hmac-secret=" + INVITATION
        ).run(context -> assertThat(context).hasNotFailed());
    }
}
