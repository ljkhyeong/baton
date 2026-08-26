package com.personal.baton.adapter.out.external.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.personal.baton.application.calendar.CalendarSnapshot;
import com.personal.baton.application.calendar.port.out.CalendarSnapshotClient.Outcome;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
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
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class RestClientCalendarSnapshotClientTest {

    private static final String BASE_URL = "https://calendar.internal";
    private static final String TOKEN = "calendar-token-with-at-least-32-characters";
    private static final String SNAPSHOT_URL = BASE_URL
            + "/internal/api/v1/schedule-snapshots";

    private MockRestServiceServer server;
    private RestClientCalendarSnapshotClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(BASE_URL)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new RestClientCalendarSnapshotClient(builder.build());
    }

    @Test
    @DisplayName("CAL client는 내부 Bearer와 같은 아웃박스 개정 번호로 전체 스냅샷을 전송한다")
    void postsAuthenticatedSnapshot() {
        server.expect(requestTo(SNAPSHOT_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.revision").value(27))
                .andExpect(jsonPath("$.time.type").value("ALL_DAY"))
                .andExpect(jsonPath("$.time.startDate").value("2026-08-26"))
                .andRespond(withSuccess(
                        "{\"result\":\"APPLIED\"}",
                        MediaType.APPLICATION_JSON
                ));

        var result = client.deliver(snapshot());

        assertThat(result.outcome()).isEqualTo(Outcome.DELIVERED);
        assertThat(result.code()).isEqualTo("APPLIED");
        server.verify();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("responses")
    @DisplayName("CAL 응답은 전달 완료와 재시도·영구 실패로 분류된다")
    void classifiesResponses(
            String description,
            HttpStatus status,
            String body,
            Outcome outcome,
            String code
    ) {
        server.expect(requestTo(SNAPSHOT_URL))
                .andRespond(withStatus(status)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body));

        var result = client.deliver(snapshot());

        assertThat(result.outcome()).as(description).isEqualTo(outcome);
        assertThat(result.code()).isEqualTo(code);
        server.verify();
    }

    @Test
    @DisplayName("CAL 네트워크 오류는 응답 원문을 저장하지 않고 재시도 대상으로 분류된다")
    void classifiesNetworkFailure() {
        server.expect(requestTo(SNAPSHOT_URL))
                .andRespond(withException(new IOException("secret URL과 token을 포함한 오류")));

        var result = client.deliver(snapshot());

        assertThat(result.outcome()).isEqualTo(Outcome.RETRYABLE_FAILURE);
        assertThat(result.code()).isEqualTo("CAL_NETWORK_FAILURE");
        server.verify();
    }

    private static Stream<Arguments> responses() {
        return Stream.of(
                Arguments.of(
                        "중복 재전달",
                        HttpStatus.OK,
                        "{\"result\":\"DUPLICATE\"}",
                        Outcome.DELIVERED,
                        "DUPLICATE"
                ),
                Arguments.of(
                        "일시적인 서버 장애",
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "{\"code\":\"INTERNAL_ERROR\"}",
                        Outcome.RETRYABLE_FAILURE,
                        "HTTP_503"
                ),
                Arguments.of(
                        "개정 번호 충돌",
                        HttpStatus.CONFLICT,
                        "{\"code\":\"SOURCE_REVISION_CONFLICT\"}",
                        Outcome.PERMANENT_FAILURE,
                        "SOURCE_REVISION_CONFLICT"
                ),
                Arguments.of(
                        "잘못된 성공 응답",
                        HttpStatus.OK,
                        "{\"result\":\"UNKNOWN\"}",
                        Outcome.PERMANENT_FAILURE,
                        "CAL_INVALID_SUCCESS_RESPONSE"
                ),
                Arguments.of(
                        "해석할 수 없는 성공 응답",
                        HttpStatus.OK,
                        "{",
                        Outcome.PERMANENT_FAILURE,
                        "CAL_INVALID_SUCCESS_RESPONSE"
                )
        );
    }

    private CalendarSnapshot snapshot() {
        Instant now = Instant.parse("2026-08-25T12:00:00Z");
        return new CalendarSnapshot(
                UUID.fromString("10000000-0000-0000-0000-000000000001"),
                now,
                UUID.fromString("20000000-0000-0000-0000-000000000001"),
                UUID.fromString("30000000-0000-0000-0000-000000000001"),
                27,
                CalendarSnapshot.Status.ACTIVE,
                "시즌 회차",
                null,
                null,
                new CalendarSnapshot.AllDay(
                        LocalDate.of(2026, 8, 26),
                        LocalDate.of(2026, 8, 27)
                ),
                now
        );
    }
}
