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

import com.personal.baton.application.calendar.CalendarSeasonMetadata;
import com.personal.baton.application.calendar.CalendarSnapshot;
import com.personal.baton.application.calendar.CalendarRecoveryManifest;
import com.personal.baton.application.calendar.port.out.CalendarSnapshotClient.Outcome;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
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
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class RestClientCalendarClientTest {

    private static final String BASE_URL = "https://calendar.internal";
    private static final String TOKEN = "calendar-token-with-at-least-32-characters";
    private static final String SNAPSHOT_URL = BASE_URL
            + "/internal/api/v1/schedule-snapshots";

    private MockRestServiceServer server;
    private RestClientCalendarClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(BASE_URL)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new RestClientCalendarClient(builder.build());
    }

    @Test
    @DisplayName("CAL 클라이언트는 내부 Bearer 토큰과 같은 아웃박스 개정 번호로 전체 스냅샷을 전송한다")
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
    @MethodSource("metadataResponses")
    @DisplayName("시즌 이름 전달은 응답의 시즌·개정 번호·이름이 요청과 맞는지 확인한다")
    void putsSeasonMetadata(String description, String body, Outcome outcome, String code) {
        UUID seasonId = UUID.fromString("30000000-0000-0000-0000-000000000001");
        server.expect(requestTo(BASE_URL + "/internal/api/v1/seasons/" + seasonId + "/calendar-metadata"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {"revision":2,"displayName":"가을 시즌"}
                        """))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        var result = client.deliver(new CalendarSeasonMetadata(seasonId, 2, "가을 시즌"));

        assertThat(result.outcome()).as(description).isEqualTo(outcome);
        assertThat(result.code()).isEqualTo(code);
        server.verify();
    }

    @Test
    @DisplayName("CAL 복구 클라이언트는 시즌 매니페스트와 전체 완료 응답을 요청 범위와 대조한다")
    void verifiesRecoveryManifestAndCompletion() {
        UUID recoveryId = UUID.fromString("40000000-0000-0000-0000-000000000001");
        UUID seasonId = UUID.fromString("30000000-0000-0000-0000-000000000001");
        var season = new CalendarRecoveryManifest.Season(
                seasonId,
                1,
                "1".repeat(64),
                2,
                "2".repeat(64)
        );
        var manifest = new CalendarRecoveryManifest(List.of(season), "3".repeat(64));
        server.expect(requestTo(BASE_URL + "/internal/api/v1/recovery-runs/" + recoveryId
                        + "/seasons/" + seasonId + "/manifest"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(content().json("""
                        {"itemCount":1,"itemDigest":"%s","metadataRevision":2,"metadataDigest":"%s"}
                        """.formatted("1".repeat(64), "2".repeat(64))))
                .andRespond(withSuccess("""
                        {"recoveryId":"%s","seasonId":"%s","result":"VERIFIED","itemCount":1,"metadataRevision":2}
                        """.formatted(recoveryId, seasonId), MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE_URL + "/internal/api/v1/recovery-runs/" + recoveryId + "/completion"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(content().json("""
                        {"seasonCount":1,"seasonDigest":"%s"}
                        """.formatted("3".repeat(64))))
                .andRespond(withSuccess("""
                        {"recoveryId":"%s","result":"COMPLETED","seasonCount":1,"completedAt":"2026-09-02T03:00:00Z"}
                        """.formatted(recoveryId), MediaType.APPLICATION_JSON));

        assertThat(client.verifySeason(recoveryId, season).outcome()).isEqualTo(Outcome.DELIVERED);
        assertThat(client.complete(recoveryId, manifest).outcome()).isEqualTo(Outcome.DELIVERED);
        server.verify();
    }

    @Test
    @DisplayName("CAL 상태가 아직 매니페스트와 다르면 복구 완료 확인을 재시도한다")
    void retriesRecoveryManifestMismatch() {
        UUID recoveryId = UUID.fromString("40000000-0000-0000-0000-000000000001");
        UUID seasonId = UUID.fromString("30000000-0000-0000-0000-000000000001");
        var season = new CalendarRecoveryManifest.Season(
                seasonId, 0, "1".repeat(64), 2, "2".repeat(64)
        );
        server.expect(requestTo(BASE_URL + "/internal/api/v1/recovery-runs/" + recoveryId
                        + "/seasons/" + seasonId + "/manifest"))
                .andRespond(withStatus(HttpStatus.CONFLICT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"RECOVERY_MANIFEST_MISMATCH\"}"));

        var result = client.verifySeason(recoveryId, season);

        assertThat(result.outcome()).isEqualTo(Outcome.RETRYABLE_FAILURE);
        assertThat(result.code()).isEqualTo("RECOVERY_MANIFEST_MISMATCH");
        server.verify();
    }

    private static Stream<Arguments> metadataResponses() {
        String accepted = """
                {"seasonId":"30000000-0000-0000-0000-000000000001","revision":2,"displayName":"가을 시즌"}
                """;
        return Stream.of(
                Arguments.of("적용 또는 동일 재전달", accepted,
                        Outcome.DELIVERED, "SEASON_METADATA_ACCEPTED"),
                Arguments.of("CAL이 더 최신 이름을 보유함", accepted.replace("2,", "3,").replace("가을", "겨울"),
                        Outcome.DELIVERED, "STALE"),
                Arguments.of("응답 시즌이 다름", accepted.replace("000000000001", "000000000002"),
                        Outcome.PERMANENT_FAILURE, "CAL_INVALID_SUCCESS_RESPONSE"),
                Arguments.of("응답 개정 번호가 낮음", accepted.replace("2,", "1,"),
                        Outcome.PERMANENT_FAILURE, "CAL_INVALID_SUCCESS_RESPONSE"),
                Arguments.of("동일 개정 번호에 다른 이름", accepted.replace("가을", "겨울"),
                        Outcome.PERMANENT_FAILURE, "CAL_INVALID_SUCCESS_RESPONSE"),
                Arguments.of("필수 개정 번호가 없음", accepted.replace("\"revision\":2,", ""),
                        Outcome.PERMANENT_FAILURE, "CAL_INVALID_SUCCESS_RESPONSE"),
                Arguments.of("최신 개정의 이름이 없음", accepted.replace("2,", "3,").replace("\"가을 시즌\"", "null"),
                        Outcome.PERMANENT_FAILURE, "CAL_INVALID_SUCCESS_RESPONSE")
        );
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

    @Test
    @DisplayName("CAL 성공 응답 본문을 읽는 중 시간 초과가 발생하면 같은 스냅샷을 재시도한다")
    void retriesWhenSuccessfulResponseBodyTimesOut() {
        server.expect(requestTo(SNAPSHOT_URL)).andRespond(request -> {
            InputStream body = new InputStream() {
                private boolean firstByte = true;

                @Override
                public int read() throws IOException {
                    if (firstByte) {
                        firstByte = false;
                        return '{';
                    }
                    throw new SocketTimeoutException("응답 본문 수신 시간 초과");
                }
            };
            var response = new MockClientHttpResponse(body, HttpStatus.OK);
            response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            return response;
        });

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
                        "교체가 필요한 인증 자격 증명",
                        HttpStatus.UNAUTHORIZED,
                        "{\"code\":\"UNAUTHORIZED\"}",
                        Outcome.RETRYABLE_FAILURE,
                        "HTTP_401"
                ),
                Arguments.of(
                        "권한 설정이 완료되지 않은 인증 자격 증명",
                        HttpStatus.FORBIDDEN,
                        "{\"code\":\"FORBIDDEN\"}",
                        Outcome.RETRYABLE_FAILURE,
                        "HTTP_403"
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
                        "필수 결과가 없는 성공 응답",
                        HttpStatus.OK,
                        "{}",
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
