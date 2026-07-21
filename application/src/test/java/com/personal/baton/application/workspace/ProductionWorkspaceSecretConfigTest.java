package com.personal.baton.application.workspace;

import com.personal.baton.bootstrap.config.ProductionWorkspaceSecretConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("usecase")
class ProductionWorkspaceSecretConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(context -> context.getEnvironment().setActiveProfiles("production"))
            .withUserConfiguration(ProductionWorkspaceSecretConfig.class);

    @DisplayName("production 프로필은 워크스페이스 생성 키가 없으면 시작을 거절한다")
    @Test
    void rejectsMissingCreationKeyInProduction() {
        contextRunner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseMessage(
                            "production 프로필에는 BATON_WORKSPACE_CREATION_KEY 설정이 필요합니다"
                    );
        });
    }

    @DisplayName("production 프로필은 워크스페이스 복구 키가 비어 있으면 시작을 거절한다")
    @Test
    void rejectsBlankRecoveryKeyInProduction() {
        contextRunner
                .withPropertyValues(
                        "baton.workspace.creation-key=production-creation-key-000000000001",
                        "baton.workspace.recovery-key= "
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage(
                                    "production 프로필에는 BATON_WORKSPACE_RECOVERY_KEY 설정이 필요합니다"
                            );
                });
    }

    @DisplayName("production 프로필은 생성 키가 32자보다 짧으면 시작을 거절한다")
    @Test
    void rejectsShortCreationKeyInProduction() {
        contextRunner
                .withPropertyValues(
                        "baton.workspace.creation-key=short-creation-key",
                        "baton.workspace.recovery-key=production-recovery-key-000000000001"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage(
                                    "production 프로필의 BATON_WORKSPACE_CREATION_KEY은(는) 최소 32자여야 합니다"
                            );
                });
    }

    @DisplayName("production 프로필은 복구 키가 32자보다 짧으면 시작을 거절한다")
    @Test
    void rejectsShortRecoveryKeyInProduction() {
        contextRunner
                .withPropertyValues(
                        "baton.workspace.creation-key=production-creation-key-000000000001",
                        "baton.workspace.recovery-key=short-recovery-key"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage(
                                    "production 프로필의 BATON_WORKSPACE_RECOVERY_KEY은(는) 최소 32자여야 합니다"
                            );
                });
    }

    @DisplayName("production 프로필은 생성 키와 복구 키가 같으면 시작을 거절한다")
    @Test
    void rejectsSameCreationAndRecoveryKeyInProduction() {
        contextRunner
                .withPropertyValues(
                        "baton.workspace.creation-key=shared-operator-key-000000000000001",
                        "baton.workspace.recovery-key=shared-operator-key-000000000000001"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage(
                                    "production 프로필의 생성 키와 복구 키는 서로 달라야 합니다"
                            );
                });
    }

    @DisplayName("production 프로필은 생성 키와 복구 키가 모두 있으면 보안 설정을 구성한다")
    @Test
    void acceptsConfiguredWorkspaceSecretsInProduction() {
        contextRunner
                .withPropertyValues(
                        "baton.workspace.creation-key=production-creation-key-000000000001",
                        "baton.workspace.recovery-key=production-recovery-key-000000000001"
                )
                .run(context -> assertThat(context).hasNotFailed());
    }
}
