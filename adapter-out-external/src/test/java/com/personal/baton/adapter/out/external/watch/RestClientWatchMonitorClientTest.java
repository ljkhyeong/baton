package com.personal.baton.adapter.out.external.watch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.personal.baton.application.watch.WatchMonitorDelivery;
import com.personal.baton.application.watch.WatchMonitoringState;
import com.personal.baton.application.watch.port.out.WatchMonitorClient.Outcome;
import com.personal.baton.application.watch.port.out.WatchMonitorClient.SynchronizationResult;
import java.io.IOException;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class RestClientWatchMonitorClientTest {

    private static final String BASE_URL = "https://watch.internal";
    private static final String TOKEN = "watch-token-with-at-least-32-characters";
    private static final UUID EVENT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID RESOURCE_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID LEASE_TOKEN = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final String RESOURCE_REFERENCE =
            "baton-manager:primary:role-resource:" + RESOURCE_ID;
    private static final String ENCODED_RESOURCE_REFERENCE = RESOURCE_REFERENCE.replace(":", "%3A");

    private MockRestServiceServer server;
    private RestClientWatchMonitorClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(BASE_URL)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new RestClientWatchMonitorClient(builder.build());
    }

    @Test
    @DisplayName("ACTIVE snapshot은 인증된 PUT 요청으로 URL과 revision을 전달한다")
    void synchronizeActiveMonitor() {
        server.expect(requestTo(BASE_URL + "/api/v1/resource-monitors/" + ENCODED_RESOURCE_REFERENCE))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.sourceRevision").value(42))
                .andExpect(jsonPath("$.monitoringState").value("ACTIVE"))
                .andExpect(jsonPath("$.targetUrl").value("https://example.com/health"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        SynchronizationResult result = client.synchronize(activeDelivery());

        assertThat(result.outcome()).isEqualTo(Outcome.DELIVERED);
        assertThat(result.code()).isNull();
        server.verify();
    }

    @Test
    @DisplayName("INACTIVE snapshot은 targetUrl을 null로 전달한다")
    void synchronizeInactiveMonitor() {
        server.expect(requestTo(BASE_URL + "/api/v1/resource-monitors/" + ENCODED_RESOURCE_REFERENCE))
                .andExpect(content().json(
                        """
                                {
                                  "sourceRevision": 43,
                                  "monitoringState": "INACTIVE",
                                  "targetUrl": null
                                }
                                """,
                        JsonCompareMode.STRICT
                ))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        SynchronizationResult result = client.synchronize(inactiveDelivery());

        assertThat(result.outcome()).isEqualTo(Outcome.DELIVERED);
        server.verify();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("errorResponses")
    @DisplayName("WATCH 오류 상태와 안정적인 code를 전달 결과로 분류한다")
    void classifyErrorResponse(
            String description,
            HttpStatus status,
            String responseBody,
            Outcome expectedOutcome,
            String expectedCode
    ) {
        server.expect(requestTo(BASE_URL + "/api/v1/resource-monitors/" + ENCODED_RESOURCE_REFERENCE))
                .andRespond(withStatus(status)
                        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body(responseBody));

        SynchronizationResult result = client.synchronize(activeDelivery());

        assertThat(result.outcome()).as(description).isEqualTo(expectedOutcome);
        assertThat(result.code()).isEqualTo(expectedCode);
        server.verify();
    }

    @Test
    @DisplayName("네트워크 예외는 응답 원문 없이 재시도 가능한 실패로 분류한다")
    void classifyNetworkFailure() {
        server.expect(requestTo(BASE_URL + "/api/v1/resource-monitors/" + ENCODED_RESOURCE_REFERENCE))
                .andRespond(withException(new IOException("target URL과 token을 포함한 원격 오류")));

        SynchronizationResult result = client.synchronize(activeDelivery());

        assertThat(result.outcome()).isEqualTo(Outcome.RETRYABLE_FAILURE);
        assertThat(result.code()).isEqualTo("WATCH_NETWORK_FAILURE");
        server.verify();
    }

    private static Stream<Arguments> errorResponses() {
        return Stream.of(
                Arguments.of(
                        "stale revision",
                        HttpStatus.CONFLICT,
                        "{\"code\":\"STALE_SOURCE_REVISION\"}",
                        Outcome.STALE,
                        "STALE_SOURCE_REVISION"
                ),
                Arguments.of(
                        "equal revision conflict",
                        HttpStatus.CONFLICT,
                        "{\"code\":\"SOURCE_REVISION_CONFLICT\"}",
                        Outcome.PERMANENT_FAILURE,
                        "SOURCE_REVISION_CONFLICT"
                ),
                Arguments.of(
                        "invalid target URL",
                        HttpStatus.UNPROCESSABLE_CONTENT,
                        "{\"code\":\"INVALID_TARGET_URL\"}",
                        Outcome.INVALID_TARGET,
                        "INVALID_TARGET_URL"
                ),
                Arguments.of(
                        "rate limit",
                        HttpStatus.TOO_MANY_REQUESTS,
                        "{\"code\":\"RATE_LIMITED\"}",
                        Outcome.RETRYABLE_FAILURE,
                        "HTTP_429"
                ),
                Arguments.of(
                        "server failure",
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "{\"code\":\"REMOTE_SECRET_DETAIL\"}",
                        Outcome.RETRYABLE_FAILURE,
                        "HTTP_503"
                ),
                Arguments.of(
                        "other client failure",
                        HttpStatus.BAD_REQUEST,
                        "{\"code\":\"https://secret.example/path\"}",
                        Outcome.PERMANENT_FAILURE,
                        "HTTP_400"
                )
        );
    }

    private WatchMonitorDelivery activeDelivery() {
        return new WatchMonitorDelivery(
                42,
                EVENT_ID,
                RESOURCE_ID,
                RESOURCE_REFERENCE,
                WatchMonitoringState.ACTIVE,
                "https://example.com/health",
                1,
                LEASE_TOKEN
        );
    }

    private WatchMonitorDelivery inactiveDelivery() {
        return new WatchMonitorDelivery(
                43,
                EVENT_ID,
                RESOURCE_ID,
                RESOURCE_REFERENCE,
                WatchMonitoringState.INACTIVE,
                null,
                1,
                LEASE_TOKEN
        );
    }
}
