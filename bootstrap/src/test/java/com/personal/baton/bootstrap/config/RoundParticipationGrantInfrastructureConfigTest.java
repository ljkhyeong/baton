package com.personal.baton.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.personal.baton.adapter.out.external.roundauth.DisabledParticipationGrantInfrastructure;
import com.personal.baton.adapter.out.external.roundauth.NimbusParticipationGrantInfrastructure;
import com.personal.baton.application.roundauth.port.out.ParticipationGrantJwkSetProvider;
import com.personal.baton.application.roundauth.port.out.ParticipationGrantSigner;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class RoundParticipationGrantInfrastructureConfigTest {

    private static KeyPair currentKeyPair;
    private static KeyPair previousKeyPair;

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(RoundParticipationGrantInfrastructureConfig.class);

    @TempDir
    private Path tempDirectory;

    private Path currentPrivateKeyPath;
    private Path currentPublicKeyPath;
    private Path previousPublicKeyPath;

    @BeforeAll
    static void generateKeys() throws NoSuchAlgorithmException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        currentKeyPair = generator.generateKeyPair();
        previousKeyPair = generator.generateKeyPair();
    }

    @BeforeEach
    void writeKeyFiles() throws IOException {
        currentPrivateKeyPath = writePem(
                "current-private.pem",
                "PRIVATE KEY",
                currentKeyPair.getPrivate().getEncoded()
        );
        currentPublicKeyPath = writePem(
                "current-public.pem",
                "PUBLIC KEY",
                currentKeyPair.getPublic().getEncoded()
        );
        previousPublicKeyPath = writePem(
                "previous-public.pem",
                "PUBLIC KEY",
                previousKeyPair.getPublic().getEncoded()
        );
    }

    @Test
    @DisplayName("ROUND 참여권 설정을 켜지 않으면 signer와 JWK 조회가 함께 fail-closed 된다")
    void configureDisabledInfrastructureByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(DisabledParticipationGrantInfrastructure.class);
            assertThat(context).hasSingleBean(ParticipationGrantSigner.class);
            assertThat(context).hasSingleBean(ParticipationGrantJwkSetProvider.class);
        });
    }

    @Test
    @DisplayName("활성 설정은 current private file과 현재·이전 public file을 하나의 회전 경계로 묶는다")
    void configureEnabledInfrastructureWithPublicKeyOverlap() {
        enabledContextRunner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(NimbusParticipationGrantInfrastructure.class);
            assertThat(context).hasSingleBean(ParticipationGrantSigner.class);
            assertThat(context).hasSingleBean(ParticipationGrantJwkSetProvider.class);
            assertThat(context.getBean(ParticipationGrantJwkSetProvider.class)
                    .readPublicJwkSetJson())
                    .contains("current-config-key")
                    .contains("previous-config-key");
        });
    }

    @Test
    @DisplayName("이전 public key의 kid와 파일 경로 중 하나만 설정하면 시작을 거부한다")
    void rejectPartialPreviousPublicKeyConfiguration() {
        currentKeyContextRunner()
                .withPropertyValues(
                        "baton.round.participation-grant.previous-public-keys[0].kid="
                                + "partial-previous-key"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage(
                            "이전 ROUND public key는 kid와 파일 경로를 함께 설정해야 합니다"
                    );
                });
    }

    @Test
    @DisplayName("활성 설정에서 issuer나 key file이 빠지면 애플리케이션 시작을 거부한다")
    void rejectIncompleteEnabledConfiguration() {
        contextRunner
                .withPropertyValues("baton.round.participation-grant.enabled=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage("ROUND 참여권 issuer는 필수입니다");
                });
        contextRunner
                .withPropertyValues(
                        "baton.round.participation-grant.enabled=true",
                        "baton.round.participation-grant.issuer=https://baton.example"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage("현재 ROUND 서명 키는 필수입니다");
                });
    }

    @Test
    @DisplayName("ROUND 참여권 설정 문자열은 key file 경로를 노출하지 않는다")
    void redactKeyPathsFromConfigurationString() {
        RoundParticipationGrantProperties properties =
                new RoundParticipationGrantProperties(
                        true,
                        "https://baton.example",
                        "round",
                        new RoundParticipationGrantProperties.SigningKey(
                                "current",
                                Path.of("/run/secrets/round-private.pem"),
                                Path.of("/run/secrets/round-public.pem")
                        ),
                        List.of(new RoundParticipationGrantProperties.PublicKey(
                                "previous",
                                Path.of("/run/secrets/round-previous.pem")
                        ))
                );

        assertThat(properties.toString())
                .doesNotContain("/run/secrets")
                .contains("currentKey=<redacted>")
                .contains("previousPublicKeys=<redacted>");
    }

    private ApplicationContextRunner enabledContextRunner() {
        return currentKeyContextRunner().withPropertyValues(
                "baton.round.participation-grant.previous-public-keys[0].kid="
                        + "previous-config-key",
                "baton.round.participation-grant.previous-public-keys[0].public-key-path="
                        + previousPublicKeyPath
        );
    }

    private ApplicationContextRunner currentKeyContextRunner() {
        return contextRunner.withPropertyValues(
                "baton.round.participation-grant.enabled=true",
                "baton.round.participation-grant.issuer=https://baton.example",
                "baton.round.participation-grant.audience=round",
                "baton.round.participation-grant.current-key.kid=current-config-key",
                "baton.round.participation-grant.current-key.private-key-path="
                        + currentPrivateKeyPath,
                "baton.round.participation-grant.current-key.public-key-path="
                        + currentPublicKeyPath
        );
    }

    private Path writePem(String fileName, String label, byte[] encoded) throws IOException {
        Path path = tempDirectory.resolve(fileName);
        String body = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(encoded);
        String pem = "-----BEGIN " + label + "-----\n"
                + body
                + "\n-----END " + label + "-----";
        return Files.writeString(path, pem, StandardCharsets.UTF_8);
    }
}
