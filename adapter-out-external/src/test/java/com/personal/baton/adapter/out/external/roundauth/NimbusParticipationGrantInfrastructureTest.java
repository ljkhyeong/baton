package com.personal.baton.adapter.out.external.roundauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import com.personal.baton.application.roundauth.error.ParticipationGrantUnavailableException;
import com.personal.baton.application.roundauth.port.out.ParticipationGrantSigner.ParticipationGrantClaims;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NimbusParticipationGrantInfrastructureTest {

    private static final UUID ACCOUNT_ID =
            UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID TEAM_ID =
            UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID TOKEN_ID =
            UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final Instant ISSUED_AT = Instant.parse("2026-08-08T11:00:00Z");
    private static final Instant EXPIRES_AT = ISSUED_AT.plusSeconds(300);

    private static KeyPair currentKeyPair;
    private static KeyPair previousKeyPair;

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
    @DisplayName("현재 RSA 키와 kid로 RS256 참여권의 고정 클레임을 서명한다")
    void signRs256ParticipationGrant() throws Exception {
        NimbusParticipationGrantInfrastructure infrastructure = infrastructure();

        SignedJWT signedJwt = SignedJWT.parse(infrastructure.sign(claims()));

        assertThat(signedJwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.RS256);
        assertThat(signedJwt.getHeader().getKeyID()).isEqualTo("current-2026-08");
        assertThat(signedJwt.verify(new RSASSAVerifier(
                (RSAPublicKey) currentKeyPair.getPublic()
        ))).isTrue();
        assertThat(signedJwt.getJWTClaimsSet().getIssuer()).isEqualTo("https://baton.example");
        assertThat(signedJwt.getJWTClaimsSet().getAudience()).containsExactly("round");
        assertThat(signedJwt.getJWTClaimsSet().getSubject()).isEqualTo(ACCOUNT_ID.toString());
        assertThat(signedJwt.getJWTClaimsSet().getJWTID()).isEqualTo(TOKEN_ID.toString());
        assertThat(signedJwt.getJWTClaimsSet().getStringClaim("study_id"))
                .isEqualTo(TEAM_ID.toString());
        assertThat(signedJwt.getJWTClaimsSet().getStringClaim("room_id"))
                .isEqualTo("abcd-efgh-jkmn");
        assertThat(signedJwt.getJWTClaimsSet().getStringClaim("role"))
                .isEqualTo("participant");
        assertThat(signedJwt.getJWTClaimsSet().getIssueTime().toInstant()).isEqualTo(ISSUED_AT);
        assertThat(signedJwt.getJWTClaimsSet().getExpirationTime().toInstant())
                .isEqualTo(EXPIRES_AT);
    }

    @Test
    @DisplayName("JWK Set은 현재와 이전 public key만 노출해 회전 overlap을 지원한다")
    void publishOnlyCurrentAndPreviousPublicKeys() throws Exception {
        JWKSet jwkSet = JWKSet.parse(infrastructure().readPublicJwkSetJson());

        assertThat(jwkSet.getKeys())
                .extracting(JWK::getKeyID)
                .containsExactly("current-2026-08", "previous-2026-07");
        assertThat(jwkSet.containsNonPublicKeys()).isFalse();
        assertThat(jwkSet.getKeys())
                .allSatisfy(key -> {
                    assertThat(key.getAlgorithm()).isEqualTo(JWSAlgorithm.RS256);
                    assertThat(key.isPrivate()).isFalse();
                });
        RSAKey previous = (RSAKey) jwkSet.getKeyByKeyId("previous-2026-07");
        assertThat(previous.toRSAPublicKey())
                .isEqualTo((RSAPublicKey) previousKeyPair.getPublic());
        assertThat(infrastructure().readPublicJwkSetJson())
                .doesNotContain("\"d\"")
                .doesNotContain("PRIVATE KEY");
    }

    @Test
    @DisplayName("kid가 허용 정규식과 정확히 일치하지 않으면 설정을 거부한다")
    void rejectInvalidKid() {
        assertThatThrownBy(() -> new NimbusParticipationGrantInfrastructure(
                "https://baton.example",
                "round",
                signingKey("bad kid", currentPrivateKeyPath, currentPublicKeyPath),
                List.of()
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    }

    @Test
    @DisplayName("현재 key와 이전 public key의 kid가 같으면 시작을 거부한다")
    void rejectDuplicateKid() {
        assertThatThrownBy(() -> new NimbusParticipationGrantInfrastructure(
                "https://baton.example",
                "round",
                signingKey("same-kid", currentPrivateKeyPath, currentPublicKeyPath),
                List.of(publicKey("same-kid", previousPublicKeyPath))
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("중복");
    }

    @Test
    @DisplayName("현재 RSA public/private key가 서로 다르면 시작을 거부한다")
    void rejectMismatchedCurrentKeyPair() {
        assertThatThrownBy(() -> new NimbusParticipationGrantInfrastructure(
                "https://baton.example",
                "round",
                new NimbusParticipationGrantInfrastructure.SigningKeyMaterial(
                        "mismatch",
                        currentPrivateKeyPath,
                        previousPublicKeyPath
                ),
                List.of()
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("서로 일치하지 않습니다");
    }

    @Test
    @DisplayName("modulus가 같아도 RSA public exponent가 다르면 key pair를 거부한다")
    void rejectMismatchedCurrentPublicExponent() throws Exception {
        RSAPublicKey currentPublicKey = (RSAPublicKey) currentKeyPair.getPublic();
        RSAPublicKey differentExponentKey = (RSAPublicKey) KeyFactory.getInstance("RSA")
                .generatePublic(new RSAPublicKeySpec(
                        currentPublicKey.getModulus(),
                        currentPublicKey.getPublicExponent().add(java.math.BigInteger.TWO)
                ));
        Path differentExponentPath = writePem(
                "different-exponent-public.pem",
                "PUBLIC KEY",
                differentExponentKey.getEncoded()
        );

        assertThatThrownBy(() -> new NimbusParticipationGrantInfrastructure(
                "https://baton.example",
                "round",
                signingKey("exponent-mismatch", currentPrivateKeyPath, differentExponentPath),
                List.of()
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("서로 일치하지 않습니다");
    }

    @Test
    @DisplayName("v1 참여권 role은 participant 외 값을 서명하지 않는다")
    void rejectUnsupportedRole() {
        ParticipationGrantClaims unsupportedClaims = new ParticipationGrantClaims(
                ACCOUNT_ID,
                TEAM_ID,
                "abcd-efgh-jkmn",
                TOKEN_ID,
                "host",
                ISSUED_AT,
                EXPIRES_AT
        );

        assertThatThrownBy(() -> infrastructure().sign(unsupportedClaims))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("participant");
    }

    @Test
    @DisplayName("roomId 클레임이 표준 4-4-4 형식이 아니면 서명하지 않는다")
    void rejectNonCanonicalRoomId() {
        ParticipationGrantClaims malformedClaims = new ParticipationGrantClaims(
                ACCOUNT_ID,
                TEAM_ID,
                "not-a-room",
                TOKEN_ID,
                "participant",
                ISSUED_AT,
                EXPIRES_AT
        );

        assertThatThrownBy(() -> infrastructure().sign(malformedClaims))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("식별자 형식");
    }

    @Test
    @DisplayName("참여권 수명이 300초가 아니면 서명하지 않는다")
    void rejectUnexpectedGrantLifetime() {
        ParticipationGrantClaims longLivedClaims = new ParticipationGrantClaims(
                ACCOUNT_ID,
                TEAM_ID,
                "abcd-efgh-jkmn",
                TOKEN_ID,
                "participant",
                ISSUED_AT,
                ISSUED_AT.plusSeconds(301)
        );

        assertThatThrownBy(() -> infrastructure().sign(longLivedClaims))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("정확히 300초");
    }

    @Test
    @DisplayName("PEM 표식이 없는 Base64 원문 키 파일은 거부한다")
    void rejectRawBase64KeyFile() throws IOException {
        Path rawPublicKey = tempDirectory.resolve("raw-public.key");
        Files.writeString(
                rawPublicKey,
                Base64.getEncoder().encodeToString(currentKeyPair.getPublic().getEncoded()),
                StandardCharsets.UTF_8
        );

        assertThatThrownBy(() -> new NimbusParticipationGrantInfrastructure(
                "https://baton.example",
                "round",
                signingKey("raw-key", currentPrivateKeyPath, rawPublicKey),
                List.of()
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PEM 형식");
    }

    @Test
    @DisplayName("서명 key 파일이 없으면 참여권 인프라 시작을 거부한다")
    void rejectMissingKeyFile() {
        assertThatThrownBy(() -> new NimbusParticipationGrantInfrastructure(
                "https://baton.example",
                "round",
                signingKey(
                        "missing-key",
                        tempDirectory.resolve("missing-private.pem"),
                        currentPublicKeyPath
                ),
                List.of()
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("파일을 읽을 수 없습니다");
    }

    @Test
    @DisplayName("issuer의 명시 포트가 유효 범위를 벗어나면 설정을 거부한다")
    void rejectInvalidIssuerPort() {
        assertThatThrownBy(() -> new NimbusParticipationGrantInfrastructure(
                "https://baton.example:0",
                "round",
                signingKey("invalid-port", currentPrivateKeyPath, currentPublicKeyPath),
                List.of()
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1~65535");
        assertThatThrownBy(() -> new NimbusParticipationGrantInfrastructure(
                "https://baton.example:65536",
                "round",
                signingKey("invalid-port", currentPrivateKeyPath, currentPublicKeyPath),
                List.of()
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1~65535");
    }

    @Test
    @DisplayName("비활성 인프라는 참여권 서명과 JWK 조회를 모두 닫는다")
    void disabledInfrastructureFailsClosed() {
        DisabledParticipationGrantInfrastructure disabled =
                new DisabledParticipationGrantInfrastructure();

        assertThatThrownBy(() -> disabled.sign(claims()))
                .isInstanceOf(ParticipationGrantUnavailableException.class)
                .hasMessageContaining("비활성화");
        assertThatThrownBy(disabled::readPublicJwkSetJson)
                .isInstanceOf(ParticipationGrantUnavailableException.class)
                .hasMessageContaining("비활성화");
    }

    private NimbusParticipationGrantInfrastructure infrastructure() {
        return new NimbusParticipationGrantInfrastructure(
                "https://baton.example",
                "round",
                signingKey(
                        "current-2026-08",
                        currentPrivateKeyPath,
                        currentPublicKeyPath
                ),
                List.of(publicKey("previous-2026-07", previousPublicKeyPath))
        );
    }

    private ParticipationGrantClaims claims() {
        return new ParticipationGrantClaims(
                ACCOUNT_ID,
                TEAM_ID,
                "abcd-efgh-jkmn",
                TOKEN_ID,
                "participant",
                ISSUED_AT,
                EXPIRES_AT
        );
    }

    private static NimbusParticipationGrantInfrastructure.SigningKeyMaterial signingKey(
            String kid,
            Path privateKeyPath,
            Path publicKeyPath
    ) {
        return new NimbusParticipationGrantInfrastructure.SigningKeyMaterial(
                kid,
                privateKeyPath,
                publicKeyPath
        );
    }

    private static NimbusParticipationGrantInfrastructure.PublicKeyMaterial publicKey(
            String kid,
            Path publicKeyPath
    ) {
        return new NimbusParticipationGrantInfrastructure.PublicKeyMaterial(
                kid,
                publicKeyPath
        );
    }

    private Path writePem(String fileName, String label, byte[] encoded) throws IOException {
        Path path = tempDirectory.resolve(fileName);
        return Files.writeString(path, pem(label, encoded), StandardCharsets.UTF_8);
    }

    private static String pem(String label, byte[] encoded) {
        String body = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(encoded);
        return "-----BEGIN " + label + "-----\n"
                + body
                + "\n-----END " + label + "-----";
    }
}
