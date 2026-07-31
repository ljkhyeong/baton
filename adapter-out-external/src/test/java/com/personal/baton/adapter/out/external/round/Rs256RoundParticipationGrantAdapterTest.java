package com.personal.baton.adapter.out.external.round;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import com.personal.baton.application.round.error.RoundGrantOperationException;
import com.personal.baton.application.round.port.out.RoundParticipationGrantPort.ParticipantGrantCommand;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class Rs256RoundParticipationGrantAdapterTest {

    private static final Instant ISSUED_AT = Instant.parse("2026-07-31T03:00:00Z");
    private static final UUID ACCOUNT_ID =
            UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID SEASON_ID =
            UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final String ACTIVE_KID = "round-2026-07";

    @TempDir
    private Path tempDirectory;

    private KeyPair activeKeyPair;
    private KeyPair retiringKeyPair;
    private Path privateKeyPath;
    private Path jwkSetPath;

    @BeforeEach
    void setUp() throws Exception {
        activeKeyPair = rsaKeyPair();
        retiringKeyPair = rsaKeyPair();
        privateKeyPath = tempDirectory.resolve("round-signing-key.pem");
        jwkSetPath = tempDirectory.resolve("round-public-jwks.json");
        Files.writeString(
                privateKeyPath,
                privateKeyPem(activeKeyPair),
                StandardCharsets.UTF_8
        );
        Files.writeString(
                jwkSetPath,
                new JWKSet(List.of(
                        publicJwk(retiringKeyPair, "round-2026-06"),
                        publicJwk(activeKeyPair, ACTIVE_KID)
                )).toString(),
                StandardCharsets.UTF_8
        );
    }

    @DisplayName("RS256 참여권은 ROUND가 요구하는 participant claim과 5분 수명을 가진다")
    @Test
    void signsParticipantGrantWithRequiredClaims() throws Exception {
        Rs256RoundParticipationGrantAdapter adapter = adapter(configuredProperties());

        var grant = adapter.issueParticipantGrant(command(
                "https://round.example/room/abcd-efgh-jkmn"
        ));
        SignedJWT jwt = SignedJWT.parse(grant.token());

        assertThat(jwt.verify(new RSASSAVerifier(
                (RSAPublicKey) activeKeyPair.getPublic()
        ))).isTrue();
        assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.RS256);
        assertThat(jwt.getHeader().getKeyID()).isEqualTo(ACTIVE_KID);
        assertThat(jwt.getJWTClaimsSet().getIssuer())
                .isEqualTo("https://baton.example");
        assertThat(jwt.getJWTClaimsSet().getAudience()).containsExactly("round");
        assertThat(jwt.getJWTClaimsSet().getSubject()).isEqualTo(ACCOUNT_ID.toString());
        assertThat(jwt.getJWTClaimsSet().getIssueTime().toInstant()).isEqualTo(ISSUED_AT);
        assertThat(jwt.getJWTClaimsSet().getExpirationTime().toInstant())
                .isEqualTo(ISSUED_AT.plusSeconds(300));
        assertThat(jwt.getJWTClaimsSet().getStringClaim("room_id"))
                .isEqualTo("abcd-efgh-jkmn");
        assertThat(jwt.getJWTClaimsSet().getStringClaim("study_id"))
                .isEqualTo(SEASON_ID.toString());
        assertThat(jwt.getJWTClaimsSet().getStringClaim("role"))
                .isEqualTo("participant");
        assertThat(UUID.fromString(jwt.getJWTClaimsSet().getJWTID()).toString())
                .isEqualTo(jwt.getJWTClaimsSet().getJWTID());
        assertThat(grant.roomId()).isEqualTo("abcd-efgh-jkmn");
        assertThat(grant.expiresAt()).isEqualTo(ISSUED_AT.plusSeconds(300));
    }

    @DisplayName("연속 발급은 같은 권한에도 매번 새로운 UUID jti를 사용한다")
    @Test
    void createsFreshTokenIdForEveryGrant() throws Exception {
        Rs256RoundParticipationGrantAdapter adapter = adapter(configuredProperties());

        SignedJWT first = SignedJWT.parse(adapter.issueParticipantGrant(command(
                "https://round.example/room/abcd-efgh-jkmn"
        )).token());
        SignedJWT second = SignedJWT.parse(adapter.issueParticipantGrant(command(
                "https://round.example/room/abcd-efgh-jkmn"
        )).token());

        assertThat(first.getJWTClaimsSet().getJWTID())
                .isNotEqualTo(second.getJWTClaimsSet().getJWTID());
    }

    @DisplayName("JWKS는 rotation 공개키를 모두 포함하되 RSA private 필드를 노출하지 않는다")
    @Test
    void publishesOnlyPublicRotationKeysWithStableEtag() throws Exception {
        Rs256RoundParticipationGrantAdapter adapter = adapter(configuredProperties());

        var first = adapter.loadPublicJwkSet();
        var second = adapter.loadPublicJwkSet();
        JWKSet published = JWKSet.parse(first.body());

        assertThat(published.getKeys()).extracting(key -> key.getKeyID())
                .containsExactly("round-2026-06", ACTIVE_KID);
        assertThat(published.getKeys()).allMatch(key -> !key.isPrivate());
        assertThat(published.getKeys())
                .allSatisfy(key -> assertThat(key.toJSONObject().keySet())
                        .containsExactlyInAnyOrder("kid", "kty", "alg", "use", "n", "e"));
        assertThat(first.body()).doesNotContain(
                "\"d\"",
                "\"p\"",
                "\"q\"",
                "\"dp\"",
                "\"dq\"",
                "\"qi\""
        );
        assertThat(first.etag()).isEqualTo(second.etag());
        assertThat(first.etag()).startsWith("sha256-");
    }

    @DisplayName("JWKS는 60초 동안 검증한 공개키 snapshot을 재사용하고 이후 다시 읽는다")
    @Test
    void cachesValidatedPublicKeyRingForBoundedWindow() throws Exception {
        Clock cacheClock = mock(Clock.class);
        when(cacheClock.instant()).thenReturn(
                ISSUED_AT,
                ISSUED_AT.plusSeconds(30),
                ISSUED_AT.plusSeconds(60)
        );
        Rs256RoundParticipationGrantAdapter adapter =
                new Rs256RoundParticipationGrantAdapter(
                        configuredProperties(),
                        cacheClock
                );

        var first = adapter.loadPublicJwkSet();
        Files.writeString(jwkSetPath, "{invalid", StandardCharsets.UTF_8);
        var cached = adapter.loadPublicJwkSet();

        assertThat(cached).isSameAs(first);
        assertCode(adapter::loadPublicJwkSet, "ROUND_GRANT_SIGNER_UNAVAILABLE");
    }

    @DisplayName("설정한 ROUND origin과 다른 자료 URL은 서명 전에 거절한다")
    @Test
    void rejectsResourceOutsideConfiguredRoundOrigin() {
        Rs256RoundParticipationGrantAdapter adapter = adapter(configuredProperties());

        assertCode(
                () -> adapter.issueParticipantGrant(command(
                        "https://attacker.example/room/abcd-efgh-jkmn"
                )),
                "ROUND_RESOURCE_NOT_ELIGIBLE"
        );
    }

    @DisplayName("query나 비정규 room 경로가 붙은 ROUND 자료는 서명 전에 거절한다")
    @Test
    void rejectsNonCanonicalRoundRoomUrl() {
        Rs256RoundParticipationGrantAdapter adapter = adapter(configuredProperties());

        assertCode(
                () -> adapter.issueParticipantGrant(command(
                        "https://round.example/room/abcd-efgh-jkmn?ticket=secret"
                )),
                "ROUND_RESOURCE_NOT_ELIGIBLE"
        );
        assertCode(
                () -> adapter.issueParticipantGrant(command(
                        "https://round.example/room/ABCD-EFGH-JKMN"
                )),
                "ROUND_RESOURCE_NOT_ELIGIBLE"
        );
    }

    @DisplayName("fallback room 식별자는 설정한 ROUND origin의 canonical 자료 URL로만 조립한다")
    @Test
    void createsCanonicalResourceUrlForFallbackRoom() {
        Rs256RoundParticipationGrantAdapter adapter = adapter(configuredProperties());

        assertThat(adapter.canonicalResourceUrl("abcd-efgh-jkmn"))
                .isEqualTo("https://round.example/room/abcd-efgh-jkmn");
    }

    @DisplayName("fallback의 비정규 room 식별자는 존재 여부를 숨기는 forbidden 오류로 거절한다")
    @Test
    void rejectsNonCanonicalFallbackRoomId() {
        Rs256RoundParticipationGrantAdapter adapter = adapter(configuredProperties());

        assertCode(
                () -> adapter.canonicalResourceUrl("ABCD-EFGH-JKMN"),
                "ROUND_GRANT_FORBIDDEN"
        );
    }

    @DisplayName("active kid의 공개키와 private key가 다르면 설정 검증이 실패한다")
    @Test
    void rejectsMismatchedActivePrivateKey() throws Exception {
        Files.writeString(
                privateKeyPath,
                privateKeyPem(retiringKeyPair),
                StandardCharsets.UTF_8
        );
        Rs256RoundParticipationGrantAdapter adapter = adapter(configuredProperties());

        assertCode(
                adapter::validateEnabledConfiguration,
                "ROUND_GRANT_SIGNER_UNAVAILABLE"
        );
    }

    @DisplayName("서명 키 파일을 읽을 수 없으면 안정적인 signer unavailable 오류를 반환한다")
    @Test
    void failsClosedWhenPrivateKeyDisappears() throws Exception {
        Rs256RoundParticipationGrantAdapter adapter = adapter(configuredProperties());
        adapter.validateEnabledConfiguration();
        Files.delete(privateKeyPath);

        assertCode(
                () -> adapter.issueParticipantGrant(command(
                        "https://round.example/room/abcd-efgh-jkmn"
                )),
                "ROUND_GRANT_SIGNER_UNAVAILABLE"
        );
    }

    private Rs256RoundParticipationGrantAdapter adapter(
            RoundParticipationGrantProperties properties
    ) {
        return new Rs256RoundParticipationGrantAdapter(
                properties,
                Clock.fixed(ISSUED_AT, ZoneOffset.UTC)
        );
    }

    private RoundParticipationGrantProperties configuredProperties() {
        RoundParticipationGrantProperties properties =
                new RoundParticipationGrantProperties();
        properties.setEnabled(true);
        properties.setIssuer(java.net.URI.create("https://baton.example"));
        properties.setAudience("round");
        properties.setTtl(Duration.ofMinutes(5));
        properties.setCookieName("__Secure-round_access");
        properties.setRoundPublicOrigin(java.net.URI.create("https://round.example"));
        properties.setActiveKid(ACTIVE_KID);
        properties.setPrivateKeyPath(privateKeyPath.toString());
        properties.setJwkSetPath(jwkSetPath.toString());
        return properties;
    }

    private ParticipantGrantCommand command(String resourceUrl) {
        return new ParticipantGrantCommand(
                ACCOUNT_ID,
                SEASON_ID,
                resourceUrl,
                ISSUED_AT
        );
    }

    private KeyPair rsaKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private RSAKey publicJwk(KeyPair keyPair, String kid) {
        return new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                .keyID(kid)
                .keyUse(KeyUse.SIGNATURE)
                .algorithm(JWSAlgorithm.RS256)
                .build();
    }

    private String privateKeyPem(KeyPair keyPair) {
        String body = Base64.getMimeEncoder(
                        64,
                        "\n".getBytes(StandardCharsets.US_ASCII)
                )
                .encodeToString(keyPair.getPrivate().getEncoded());
        return "-----BEGIN PRIVATE KEY-----\n"
                + body
                + "\n-----END PRIVATE KEY-----\n";
    }

    private void assertCode(Runnable invocation, String expectedCode) {
        assertThatThrownBy(invocation::run)
                .isInstanceOfSatisfying(
                        RoundGrantOperationException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo(expectedCode)
                );
    }
}
