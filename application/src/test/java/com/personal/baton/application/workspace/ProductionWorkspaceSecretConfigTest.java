package com.personal.baton.application.workspace;

import com.personal.baton.bootstrap.config.ProductionWorkspaceSecretConfig;
import com.personal.baton.bootstrap.config.WorkspaceSecretConfig;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

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

    @DisplayName("운영 프로필은 워크스페이스 생성 키가 없으면 시작을 거절한다")
    @Test
    void rejectsMissingCreationKeyInProduction() {
        productionContextRunner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseMessage(
                            "운영 프로필에는 BATON_WORKSPACE_CREATION_KEY 설정이 필요합니다"
                    );
        });
    }

    @DisplayName("운영 프로필은 워크스페이스 복구 키가 비어 있으면 시작을 거절한다")
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
                                    "운영 프로필에는 BATON_WORKSPACE_RECOVERY_KEY 설정이 필요합니다"
                            );
                });
    }

    @DisplayName("설정한 워크스페이스 운영 키가 32~200자 URL 안전 ASCII가 아니면 프로필과 관계없이 바인딩을 거절한다")
    @ParameterizedTest
    @MethodSource("invalidWorkspaceSecrets")
    void rejectsInvalidWorkspaceSecretInEveryProfile(String secretProperty, String boundField) {
        localContextRunner
                .withPropertyValues(secretProperty)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(BindValidationException.class)
                            .hasStackTraceContaining(boundField)
                            .hasStackTraceContaining(
                                    "32~200자의 URL 안전 ASCII 문자이거나 비어 있어야 합니다"
                            );
                });
    }

    static Stream<Arguments> invalidWorkspaceSecrets() {
        return Stream.of(
                argumentSet("32자보다 짧은 생성 키",
                        "baton.workspace.creation-key=short-creation-key", "creationKey"),
                argumentSet("32자보다 짧은 복구 키",
                        "baton.workspace.recovery-key=short-recovery-key", "recoveryKey"),
                argumentSet("200자보다 긴 생성 키",
                        "baton.workspace.creation-key=" + "a".repeat(201), "creationKey"),
                argumentSet("URL 안전 ASCII가 아닌 생성 키",
                        "baton.workspace.creation-key=production/creation/key/000000000001", "creationKey")
        );
    }

    @DisplayName("운영 프로필은 생성 키와 복구 키가 같으면 시작을 거절한다")
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
                                    "운영 프로필의 생성 키와 복구 키는 서로 달라야 합니다"
                            );
                });
    }

    @DisplayName("운영 프로필은 생성 키와 복구 키가 모두 있으면 보안 설정을 구성한다")
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
