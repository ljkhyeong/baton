package com.personal.baton.application.workspace;

import com.personal.baton.bootstrap.config.ProductionWorkspaceSecretConfig;
import com.personal.baton.bootstrap.config.WorkspaceSecretConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("usecase")
class ProductionWorkspaceSecretConfigTest {

    private final ApplicationContextRunner localContextRunner = new ApplicationContextRunner()
            .withUserConfiguration(WorkspaceSecretConfig.class);

    private final ApplicationContextRunner productionContextRunner = localContextRunner
            .withInitializer(context -> context.getEnvironment().setActiveProfiles("production"))
            .withUserConfiguration(ProductionWorkspaceSecretConfig.class);

    @DisplayName("local 프로필은 비어 있는 워크스페이스 운영 키를 기본값으로 바인딩한다")
    @Test
    void bindsUnconfiguredWorkspaceSecretsInLocalProfile() {
        localContextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(WorkspaceSecrets.class))
                    .isEqualTo(new WorkspaceSecrets("", ""));
        });
    }

    @DisplayName("production 프로필은 워크스페이스 생성 키가 없으면 시작을 거절한다")
    @Test
    void rejectsMissingCreationKeyInProduction() {
        productionContextRunner.run(context -> {
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
        productionContextRunner
                .withPropertyValues(
                        "baton.workspace.creation-key=production-creation-key-000000000001",
                        "baton.workspace.recovery-key="
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage(
                                    "production 프로필에는 BATON_WORKSPACE_RECOVERY_KEY 설정이 필요합니다"
                            );
                });
    }

    @DisplayName("설정한 생성 키가 32자보다 짧으면 프로필과 관계없이 바인딩을 거절한다")
    @Test
    void rejectsShortCreationKeyInEveryProfile() {
        localContextRunner
                .withPropertyValues(
                        "baton.workspace.creation-key=short-creation-key"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(BindValidationException.class)
                            .hasStackTraceContaining("creationKey")
                            .hasStackTraceContaining(
                                    "32~200자의 URL-safe ASCII 문자이거나 비어 있어야 합니다"
                            );
                });
    }

    @DisplayName("설정한 복구 키가 32자보다 짧으면 프로필과 관계없이 바인딩을 거절한다")
    @Test
    void rejectsShortRecoveryKeyInEveryProfile() {
        localContextRunner
                .withPropertyValues(
                        "baton.workspace.recovery-key=short-recovery-key"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(BindValidationException.class)
                            .hasStackTraceContaining("recoveryKey")
                            .hasStackTraceContaining(
                                    "32~200자의 URL-safe ASCII 문자이거나 비어 있어야 합니다"
                            );
                });
    }

    @DisplayName("설정한 워크스페이스 운영 키가 200자보다 길면 바인딩을 거절한다")
    @Test
    void rejectsTooLongWorkspaceSecret() {
        localContextRunner
                .withPropertyValues(
                        "baton.workspace.creation-key=" + "a".repeat(201)
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(BindValidationException.class)
                            .hasStackTraceContaining("creationKey")
                            .hasStackTraceContaining(
                                    "32~200자의 URL-safe ASCII 문자이거나 비어 있어야 합니다"
                            );
                });
    }

    @DisplayName("URL-safe ASCII가 아닌 워크스페이스 운영 키는 바인딩을 거절한다")
    @Test
    void rejectsUnsafeWorkspaceSecret() {
        localContextRunner
                .withPropertyValues(
                        "baton.workspace.creation-key=production/creation/key/000000000001"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(BindValidationException.class)
                            .hasStackTraceContaining("creationKey")
                            .hasStackTraceContaining(
                                    "32~200자의 URL-safe ASCII 문자이거나 비어 있어야 합니다"
                            );
                });
    }

    @DisplayName("production 프로필은 생성 키와 복구 키가 같으면 시작을 거절한다")
    @Test
    void rejectsSameCreationAndRecoveryKeyInProduction() {
        productionContextRunner
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
        productionContextRunner
                .withPropertyValues(
                        "baton.workspace.creation-key=production-creation-key-000000000001",
                        "baton.workspace.recovery-key=production-recovery-key-000000000001"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(WorkspaceSecrets.class))
                            .isEqualTo(new WorkspaceSecrets(
                                    "production-creation-key-000000000001",
                                    "production-recovery-key-000000000001"
                            ));
                });
    }
}
