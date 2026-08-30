package com.personal.baton.adapter.in.web.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.http.MediaType;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

class OAuth2OutboundClientsTest {

    private static final String ISSUER = "https://oidc.example.test";
    private static final String JWK_SET_URI = ISSUER + "/jwks";
    private static final String SUBJECT = "oidc-test-user";

    private final ClientRegistration registration = CommonOAuth2Provider.GOOGLE
            .getBuilder("google")
            .clientId("baton-google-client")
            .clientSecret("test-client-secret")
            .issuerUri(ISSUER)
            .jwkSetUri(JWK_SET_URI)
            .build();

    private MockRestServiceServer server;
    private JwtDecoderFactory<ClientRegistration> factory;

    @BeforeEach
    void setUp() {
        RestTemplate mockRestTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(mockRestTemplate).build();
        RestTemplateBuilder builder = new RestTemplateBuilder(
                template -> template.setRequestFactory(mockRestTemplate.getRequestFactory())
        );
        factory = new OAuth2OutboundClients(builder, RestClient.builder())
                .oidcIdTokenDecoderFactory();
    }

    @DisplayName("같은 OIDC 등록은 공개키 캐시를 재사용하고 새 서명 키가 오면 다시 조회한다")
    @Test
    void reusesDecoderAndRefreshesRotatedSigningKey() throws Exception {
        RSAKey originalKey = new RSAKeyGenerator(2048).keyID("original-key").generate();
        RSAKey rotatedKey = new RSAKeyGenerator(2048).keyID("rotated-key").generate();
        server.expect(requestTo(JWK_SET_URI))
                .andRespond(withSuccess(
                        new JWKSet(originalKey.toPublicJWK()).toString(),
                        MediaType.APPLICATION_JSON
                ));
        server.expect(requestTo(JWK_SET_URI))
                .andRespond(withSuccess(
                        new JWKSet(rotatedKey.toPublicJWK()).toString(),
                        MediaType.APPLICATION_JSON
                ));
        String originalToken = token(originalKey, registration.getClientId());
        JwtDecoder firstDecoder = factory.createDecoder(registration);
        assertThat(firstDecoder.decode(originalToken).getSubject()).isEqualTo(SUBJECT);

        JwtDecoder reusedDecoder = factory.createDecoder(registration);

        assertThat(reusedDecoder).isSameAs(firstDecoder);
        assertThat(reusedDecoder.decode(originalToken).getSubject()).isEqualTo(SUBJECT);
        assertThat(reusedDecoder.decode(token(rotatedKey, registration.getClientId())).getSubject())
                .isEqualTo(SUBJECT);
        server.verify();
    }

    @DisplayName("같은 등록 ID라도 클라이언트 설정이 바뀌면 이전 디코더의 수신자 검증을 재사용하지 않는다")
    @Test
    void keepsValidationBoundToClientRegistration() throws Exception {
        RSAKey key = new RSAKeyGenerator(2048).keyID("shared-key").generate();
        server.expect(ExpectedCount.twice(), requestTo(JWK_SET_URI))
                .andRespond(withSuccess(
                        new JWKSet(key.toPublicJWK()).toString(),
                        MediaType.APPLICATION_JSON
                ));
        ClientRegistration changedRegistration = ClientRegistration.withClientRegistration(registration)
                .clientId("changed-google-client")
                .build();
        JwtDecoder originalDecoder = factory.createDecoder(registration);
        JwtDecoder changedDecoder = factory.createDecoder(changedRegistration);
        String originalToken = token(key, registration.getClientId());
        String changedToken = token(key, changedRegistration.getClientId());

        assertThat(originalDecoder.decode(originalToken).getSubject()).isEqualTo(SUBJECT);
        assertThat(changedDecoder.decode(changedToken).getSubject()).isEqualTo(SUBJECT);
        assertThatThrownBy(() -> changedDecoder.decode(originalToken))
                .isInstanceOf(JwtValidationException.class);
        assertThatThrownBy(() -> originalDecoder.decode(changedToken))
                .isInstanceOf(JwtValidationException.class);
        server.verify();
    }

    private String token(RSAKey key, String audience) throws Exception {
        Instant now = Instant.now(Clock.systemUTC());
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                new JWTClaimsSet.Builder()
                        .issuer(ISSUER)
                        .subject(SUBJECT)
                        .audience(audience)
                        .issueTime(Date.from(now))
                        .expirationTime(Date.from(now.plusSeconds(300)))
                        .build()
        );
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }
}
