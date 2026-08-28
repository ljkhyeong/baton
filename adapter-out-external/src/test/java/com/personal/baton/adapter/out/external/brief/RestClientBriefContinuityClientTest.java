package com.personal.baton.adapter.out.external.brief;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import com.personal.baton.application.brief.BriefContinuityDelivery;
import com.personal.baton.application.brief.BriefContinuityEvent;
import com.personal.baton.application.brief.port.out.BriefContinuityClient.Outcome;
import com.personal.baton.application.workspace.port.in.ContinuitySignalSeverity;
import com.personal.baton.application.workspace.port.in.ContinuitySignalType;
import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class RestClientBriefContinuityClientTest {

    private static final String BASE_URL = "https://brief.internal";
    private static final String BEARER_TOKEN = "brief-event-receiver-test-token-00000001";
    private MockRestServiceServer server;
    private RestClientBriefContinuityClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(BASE_URL)
                .defaultHeaders(headers -> headers.setBearerAuth(BEARER_TOKEN));
        server = MockRestServiceServer.bindTo(builder).build();
        client = new RestClientBriefContinuityClient(builder.build());
    }

    @DisplayName("BRIEF v2 이벤트를 계약 JSON으로 전송하고 202를 완료로 분류한다")
    @Test
    void sendsVersionTwoEvent() {
        server.expect(requestTo(BASE_URL + "/api/v1/events"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + BEARER_TOKEN))
                .andExpect(content().json(
                        """
                        {
                          "eventId": "00000000-0000-0000-0000-000000002321",
                          "eventType": "ROLE_UNASSIGNED",
                          "eventVersion": 2,
                          "sourceSeverity": "CRITICAL",
                          "workspaceId": "00000000-0000-0000-0000-000000002322",
                          "seasonId": "00000000-0000-0000-0000-000000002323",
                          "sourceReference": "baton-continuity:00000000-0000-0000-0000-000000002324",
                          "aggregateRevision": 1,
                          "occurredAt": "2026-08-27T03:00:00Z",
                          "state": "ACTIVE"
                        }
                        """,
                        JsonCompareMode.STRICT
                ))
                .andRespond(withStatus(HttpStatus.ACCEPTED));

        var result = client.deliver(delivery());

        assertThat(result.outcome()).isEqualTo(Outcome.DELIVERED);
        assertThat(result.code()).isEqualTo("HTTP_202");
        server.verify();
    }

    @ParameterizedTest(name = "HTTP {0}")
    @MethodSource("responseClassifications")
    @DisplayName("BRIEF HTTP 상태를 완료, 재시도, 영구 실패로 분류한다")
    void classifiesResponse(HttpStatus status, Outcome expectedOutcome) {
        server.expect(requestTo(BASE_URL + "/api/v1/events"))
                .andRespond(withStatus(status));

        var result = client.deliver(delivery());

        assertThat(result.outcome()).isEqualTo(expectedOutcome);
        assertThat(result.code()).isEqualTo("HTTP_" + status.value());
        server.verify();
    }

    @DisplayName("BRIEF 네트워크 실패는 재시도 대상으로 분류한다")
    @Test
    void retriesNetworkFailure() {
        server.expect(requestTo(BASE_URL + "/api/v1/events"))
                .andRespond(withException(new IOException("connection reset")));

        var result = client.deliver(delivery());

        assertThat(result.outcome()).isEqualTo(Outcome.RETRYABLE_FAILURE);
        assertThat(result.code()).isEqualTo("BRIEF_NETWORK_FAILURE");
        server.verify();
    }

    private static Stream<Arguments> responseClassifications() {
        return Stream.of(
                Arguments.of(HttpStatus.OK, Outcome.DELIVERED),
                Arguments.of(HttpStatus.UNAUTHORIZED, Outcome.PERMANENT_FAILURE),
                Arguments.of(HttpStatus.BAD_REQUEST, Outcome.PERMANENT_FAILURE),
                Arguments.of(HttpStatus.CONFLICT, Outcome.PERMANENT_FAILURE),
                Arguments.of(HttpStatus.UNPROCESSABLE_CONTENT, Outcome.PERMANENT_FAILURE),
                Arguments.of(HttpStatus.TOO_MANY_REQUESTS, Outcome.RETRYABLE_FAILURE),
                Arguments.of(HttpStatus.SERVICE_UNAVAILABLE, Outcome.RETRYABLE_FAILURE)
        );
    }

    private BriefContinuityDelivery delivery() {
        return new BriefContinuityDelivery(
                1,
                new BriefContinuityEvent(
                        UUID.fromString("00000000-0000-0000-0000-000000002321"),
                        ContinuitySignalType.ROLE_UNASSIGNED,
                        2,
                        ContinuitySignalSeverity.CRITICAL,
                        UUID.fromString("00000000-0000-0000-0000-000000002322"),
                        UUID.fromString("00000000-0000-0000-0000-000000002323"),
                        "baton-continuity:00000000-0000-0000-0000-000000002324",
                        1,
                        Instant.parse("2026-08-27T03:00:00Z"),
                        BriefContinuityEvent.State.ACTIVE
                ),
                1,
                UUID.fromString("00000000-0000-0000-0000-000000002325")
        );
    }
}
