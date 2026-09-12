package com.personal.baton.adapter.out.external.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.personal.baton.application.identity.port.out.HumanVerificationPort.HumanVerificationAttempt;
import com.personal.baton.application.identity.port.out.HumanVerificationPort.VerificationOutcome;
import java.io.IOException;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class TurnstileHumanVerificationAdapterTest {

    private static final String BASE_URL = "https://challenges.cloudflare.com";
    private MockRestServiceServer server;
    private TurnstileHumanVerificationAdapter adapter;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        adapter = new TurnstileHumanVerificationAdapter(
                builder.build(),
                "site-key",
                "secret-key",
                "b4ton.com"
        );
    }

    @Test
    @DisplayName("Siteverify가 호스트와 동작을 확인하면 요청을 허용한다")
    void verifiesTokenWithExpectedContext() {
        server.expect(requestTo(BASE_URL + "/turnstile/v0/siteverify"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(allOf(
                        containsString("secret=secret-key"),
                        containsString("response=verified-token"),
                        containsString("remoteip=192.0.2.10")
                )))
                .andRespond(withSuccess(
                        """
                        {
                          "success": true,
                          "hostname": "b4ton.com",
                          "action": "local_registration",
                          "error-codes": []
                        }
                        """,
                        MediaType.APPLICATION_JSON
                ));

        VerificationOutcome outcome = adapter.verify(new HumanVerificationAttempt(
                "verified-token",
                "192.0.2.10",
                "local_registration"
        ));

        assertThat(outcome).isEqualTo(VerificationOutcome.VERIFIED);
        server.verify();
    }

    @Test
    @DisplayName("만료 token과 다른 호스트·동작의 성공 응답을 거부한다")
    void rejectsInvalidOrMismatchedVerification() {
        server.expect(requestTo(BASE_URL + "/turnstile/v0/siteverify"))
                .andRespond(withSuccess(
                        """
                        {
                          "success": false,
                          "error-codes": ["timeout-or-duplicate"]
                        }
                        """,
                        MediaType.APPLICATION_JSON
                ));
        server.expect(requestTo(BASE_URL + "/turnstile/v0/siteverify"))
                .andRespond(withSuccess(
                        """
                        {
                          "success": true,
                          "hostname": "attacker.example",
                          "action": "password_reset_request"
                        }
                        """,
                        MediaType.APPLICATION_JSON
                ));

        assertThat(adapter.verify(attempt())).isEqualTo(VerificationOutcome.REJECTED);
        assertThat(adapter.verify(attempt())).isEqualTo(VerificationOutcome.REJECTED);
        server.verify();
    }

    @Test
    @DisplayName("공급자 설정 오류와 네트워크 실패는 일시 장애로 분류한다")
    void classifiesProviderFailuresAsUnavailable() {
        server.expect(requestTo(BASE_URL + "/turnstile/v0/siteverify"))
                .andRespond(withSuccess(
                        """
                        {
                          "success": false,
                          "error-codes": ["invalid-input-secret"]
                        }
                        """,
                        MediaType.APPLICATION_JSON
                ));
        server.expect(requestTo(BASE_URL + "/turnstile/v0/siteverify"))
                .andRespond(withException(new IOException("connection reset")));

        assertThat(adapter.verify(attempt())).isEqualTo(VerificationOutcome.UNAVAILABLE);
        assertThat(adapter.verify(attempt())).isEqualTo(VerificationOutcome.UNAVAILABLE);
        server.verify();
    }

    @Test
    @DisplayName("활성 설정은 비밀 키와 DNS 호스트 형식을 검증하고 로그에서 비밀을 가린다")
    void validatesEnabledConfigurationWithoutExposingSecret() {
        var properties = new TurnstileProperties(
                true,
                "site-key",
                "private-secret-key",
                "https://b4ton.com",
                Duration.ofSeconds(2),
                Duration.ofSeconds(5)
        );

        assertThat(org.assertj.core.api.Assertions.catchThrowable(properties::validateEnabled))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("호스트 형식");
        assertThat(properties.toString())
                .contains("secretKey=<redacted>")
                .doesNotContain("private-secret-key");
    }

    private HumanVerificationAttempt attempt() {
        return new HumanVerificationAttempt(
                "verified-token",
                "192.0.2.10",
                "local_registration"
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"success\":true,\"hostname\":\"b4ton.com\",\"action\":\"password_reset_request\"}",
            "{\"success\":true,\"hostname\":\"other.example\",\"action\":\"local_registration\"}"
    })
    @DisplayName("호스트와 동작 중 하나만 달라도 성공 토큰을 거부한다")
    void rejectsDifferentContext(String response) {
        server.expect(requestTo(BASE_URL + "/turnstile/v0/siteverify"))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        assertThat(adapter.verify(attempt())).isEqualTo(VerificationOutcome.REJECTED);
        server.verify();
    }

    @Test
    @DisplayName("토큰이 없으면 외부 호출 없이 거부한다")
    void rejectsMissingTokenLocally() {
        assertThat(adapter.verify(new HumanVerificationAttempt(null, "192.0.2.10", "local_registration")))
                .isEqualTo(VerificationOutcome.REJECTED);
        server.verify();
    }

    @Test
    @DisplayName("공급자의 잘못된 응답은 검증 성공으로 처리하지 않는다")
    void rejectsMalformedProviderResponse() {
        server.expect(requestTo(BASE_URL + "/turnstile/v0/siteverify"))
                .andRespond(withSuccess("not-json", MediaType.APPLICATION_JSON));
        assertThat(adapter.verify(attempt())).isEqualTo(VerificationOutcome.UNAVAILABLE);
        server.verify();
    }
}
