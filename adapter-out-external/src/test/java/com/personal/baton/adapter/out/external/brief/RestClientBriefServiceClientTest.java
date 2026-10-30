package com.personal.baton.adapter.out.external.brief;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import com.personal.baton.application.brief.port.out.BriefServiceClient.Outcome;
import com.personal.baton.application.brief.BriefAttentionPage;
import com.personal.baton.application.brief.BriefAttentionTransitions;
import com.personal.baton.application.brief.BriefEditionSnapshot;
import com.personal.baton.application.brief.error.BriefAttentionQueryRejectedException;
import com.personal.baton.application.brief.error.BriefEditionNotFoundException;
import com.personal.baton.application.brief.error.BriefIntegrationConfigurationException;
import com.personal.baton.application.brief.error.BriefIntegrationUnavailableException;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class RestClientBriefServiceClientTest {

    private static final String BASE_URL = "https://brief-service.internal";
    private static final String BEARER_TOKEN =
            "brief-service-api-test-token-00000000001";
    private static final UUID TEAM_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000002611"
    );
    private static final UUID SEASON_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000002612"
    );
    private MockRestServiceServer server;
    private RestClientBriefServiceClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(BASE_URL)
                .defaultHeaders(headers -> headers.setBearerAuth(BEARER_TOKEN));
        server = MockRestServiceServer.bindTo(builder).build();
        client = new RestClientBriefServiceClient(builder.build());
    }

    @Test
    @DisplayName("주간 해소는 요청 기간을 보존하고 다른 기간의 요약을 거부한다")
    void validatesWeeklyResolutionWindow() {
        String body = """
                {"weekStart":"2026-03-02","zoneId":"America/New_York","windowStart":"2026-03-02T05:00:00Z",
                 "windowEnd":"2026-03-09T04:00:00Z","evaluatedAt":"2026-03-08T12:00:00Z","resolvedCount":2,"items":[{"reasonCode":"ROLE_UNASSIGNED","sourceReference":"role:b",
                 "resolvedAt":"2026-03-08T00:00:00Z","resolvedRevision":2}],"nextCursor":{"eventType":"ROLE_UNASSIGNED","sourceReference":"role:b"}}
                """;
        server.expect(request -> {
                    assertThat(request.getURI().getPath()).endsWith("/attention-items/resolutions");
                    assertThat(request.getURI().getRawQuery()).isEqualTo("weekStart=2026-03-02&zoneId=America%2FNew_York&limit=1"
                            + "&afterEventType=ROLE_UNASSIGNED&afterSourceReference=role%3A%2B%26%20%ED%95%9C%EA%B8%80");
                }).andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body(body));
        server.expect(request -> {}).andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON)
                .body(body.replace("2026-03-09T04:00:00Z", "2026-03-09T05:00:00Z")));
        server.expect(request -> {}).andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON)
                .body(body.replace("\"items\"", "\"missingItems\"")));
        assertThat(client.summarizeWeeklyResolutions(TEAM_ID, SEASON_ID, LocalDate.parse("2026-03-02"), ZoneId.of("America/New_York"),
                new BriefAttentionPage.Cursor(BriefAttentionPage.EventType.ROLE_UNASSIGNED, "role:+& 한글"), 1).resolvedCount()).isEqualTo(2);
        assertThatThrownBy(() -> client.summarizeWeeklyResolutions(TEAM_ID, SEASON_ID, LocalDate.parse("2026-03-02"), ZoneId.of("America/New_York"), null, 20))
                .isInstanceOf(BriefIntegrationConfigurationException.class);
        assertThatThrownBy(() -> client.summarizeWeeklyResolutions(TEAM_ID, SEASON_ID, LocalDate.parse("2026-03-02"), ZoneId.of("America/New_York"), null, 20))
                .isInstanceOf(BriefIntegrationConfigurationException.class);
        server.verify();
    }

    @Test
    @DisplayName("시간대 ID의 더하기 기호는 쿼리에서 공백으로 바뀌지 않게 인코딩한다")
    void encodesPlusInZoneIdQuery() {
        ZoneId zoneId = ZoneId.of("Etc/GMT+9");
        String expectedQuery = "weekStart=2026-03-02&zoneId=Etc%2FGMT%2B9";
        server.expect(request -> assertThat(request.getURI().getRawQuery()).isEqualTo(expectedQuery + "&limit=1"))
                .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body("""
                        {"weekStart":"2026-03-02","zoneId":"Etc/GMT+9","windowStart":"2026-03-02T09:00:00Z",
                         "windowEnd":"2026-03-09T09:00:00Z","evaluatedAt":"2026-03-03T00:00:00Z","resolvedCount":0,"items":[]}
                        """));
        server.expect(request -> assertThat(request.getURI().getRawQuery()).isEqualTo(expectedQuery))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(client.summarizeWeeklyResolutions(TEAM_ID, SEASON_ID, LocalDate.parse("2026-03-02"), zoneId, null, 1)
                .resolvedCount()).isZero();
        assertThat(client.findLatestEditionForWeek(TEAM_ID, SEASON_ID, LocalDate.parse("2026-03-02"), zoneId).outcome())
                .isEqualTo(Outcome.NOT_FOUND);
        server.verify();
    }

    @Test
    @DisplayName("관심 항목 필터의 false 값과 특수 문자가 있는 커서를 그대로 전달한다")
    void preservesAttentionFiltersAndEncodedCursor() {
        server.expect(request -> {
                    assertThat(request.getURI().getRawQuery()).isEqualTo(
                            "status=RESOLVED&limit=1&severity=HIGH&revisionGap=false"
                                    + "&afterEventType=ROLE_UNASSIGNED&afterSourceReference=role%3A%2B%26%20%ED%95%9C%EA%B8%80");
                })
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + BEARER_TOKEN))
                .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"items\":[],\"nextCursor\":null}"));
        var page = client.findAttentionItems(TEAM_ID, SEASON_ID, new BriefAttentionPage.Filter(
                BriefAttentionPage.Status.RESOLVED, BriefAttentionPage.Severity.HIGH, false,
                new BriefAttentionPage.Cursor(BriefAttentionPage.EventType.ROLE_UNASSIGNED, "role:+& 한글"), 1));
        assertThat(page.items()).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("요약 누락 필드는 빈 요약으로 바꾸지 않고 계약 오류로 분류한다")
    void rejectsMissingSummaryField() {
        server.expect(requestTo(BASE_URL + "/api/v1/workspaces/" + TEAM_ID + "/seasons/" + SEASON_ID + "/attention-items/summary"))
                .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"highCount\":0,\"mediumCount\":0}"));
        assertThatThrownBy(() -> client.summarizeAttention(TEAM_ID, SEASON_ID))
                .isInstanceOf(BriefIntegrationConfigurationException.class);
    }

    @Test
    @DisplayName("상태 전이 참조와 리비전 커서를 그대로 보내고 공백 필드 누락을 거부한다")
    void readsTransitionsAndRejectsMissingEvidence() {
        var query = new BriefAttentionTransitions.Query(BriefAttentionPage.EventType.ROLE_UNASSIGNED, "role:+& 한글", 9L, 1);
        server.expect(request -> assertThat(request.getURI().getRawQuery()).isEqualTo(
                        "eventType=ROLE_UNASSIGNED&sourceReference=role%3A%2B%26%20%ED%95%9C%EA%B8%80&limit=1&beforeAggregateRevision=9"))
                .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body("""
                        {"transitions":[{"eventId":"00000000-0000-0000-0000-000000002701",
                        "aggregateRevision":7,"state":"RESOLVED","observedAt":"2026-08-31T00:00:00Z"}],
                        "nextBeforeAggregateRevision":null}
                        """));
        assertThatThrownBy(() -> client.findAttentionTransitions(TEAM_ID, SEASON_ID, query))
                .isInstanceOf(BriefIntegrationConfigurationException.class);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 503})
    @DisplayName("관심 항목 조건 오류와 서비스 인증 오류 및 장애를 구분한다")
    void classifiesAttentionFailures(int status) {
        server.expect(request -> {}).andRespond(withStatus(HttpStatus.valueOf(status)));
        assertThatThrownBy(() -> client.findAttentionItems(TEAM_ID, SEASON_ID,
                new BriefAttentionPage.Filter(BriefAttentionPage.Status.ACTIVE, null, null, null, 20)))
                .isInstanceOf(status == 400 ? BriefAttentionQueryRejectedException.class
                        : status == 401 ? BriefIntegrationConfigurationException.class
                        : BriefIntegrationUnavailableException.class);
    }

    @DisplayName("전용 Bearer 토큰으로 최신 주간 요약을 조회하고 ETag를 보존한다")
    @Test
    void readsLatestEditionWithServiceBearer() {
        server.expect(requestTo(BASE_URL + latestPath()))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + BEARER_TOKEN))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.ETAG, "\"brief-edition-v1-test\"")
                        .body(editionJson()));

        var result = client.findLatestEdition(TEAM_ID, SEASON_ID);

        assertThat(result.outcome()).isEqualTo(Outcome.COMPLETED);
        assertThat(result.edition().workspaceId()).isEqualTo(TEAM_ID);
        assertThat(result.etag()).isEqualTo("\"brief-edition-v1-test\"");
        server.verify();
    }

    @Test
    @DisplayName("미해결 시작 시각을 읽고 이 필드가 없는 이전 BRIEF 응답은 null로 받는다")
    void readsActiveSinceAndAcceptsPreviousBrief() {
        String item = """
                {"reasonCode":"ROLE_UNASSIGNED","severity":"HIGH","sourceReference":"role:%s","status":"ACTIVE",
                 "observedAt":"2026-08-31T00:00:00Z",%s"aggregateRevision":3,"ruleVersion":1,"revisionGap":false}
                """;
        server.expect(request -> {}).andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON)
                .body("{\"items\":[" + item.formatted("current", "\"activeSince\":\"2026-08-24T00:00:00Z\",")
                        + "," + item.formatted("previous", "") + "],\"nextCursor\":null}"));
        String editionItem = """
                {"sourceReference":"role:%s","reasonCode":"ROLE_UNASSIGNED","severity":"HIGH","status":"ACTIVE",
                 "observedAt":"2026-08-28T00:00:00Z",%s"ruleVersion":1,"aggregateRevision":2,"revisionGap":false,"section":"CARRY_OVER"}
                """;
        server.expect(requestTo(BASE_URL + latestPath())).andRespond(withStatus(HttpStatus.OK)
                .contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.ETAG, "\"brief-edition-v3-test\"")
                .body(editionJson().replace("\"items\": []", "\"items\": [" + editionItem.formatted("current",
                        "\"activeSince\":\"2026-08-14T00:00:00Z\",") + "," + editionItem.formatted("previous", "") + "]")));

        var page = client.findAttentionItems(TEAM_ID, SEASON_ID,
                new BriefAttentionPage.Filter(BriefAttentionPage.Status.ACTIVE, null, null, null, 20));
        var edition = client.findLatestEdition(TEAM_ID, SEASON_ID);

        assertThat(page.items()).extracting(BriefAttentionPage.Item::activeSince)
                .containsExactly(Instant.parse("2026-08-24T00:00:00Z"), null);
        assertThat(edition.outcome()).isEqualTo(Outcome.COMPLETED);
        assertThat(edition.edition().items()).extracting(BriefEditionSnapshot.Item::activeSince)
                .containsExactly(Instant.parse("2026-08-14T00:00:00Z"), null);
        server.verify();
    }

    @Test
    @DisplayName("비교와 최신 여부 확인은 팀·시즌 경로로 요청하고 범위 밖 요약의 404를 없음으로 처리한다")
    void readsEditionsWithinScope() {
        UUID baseId = UUID.fromString("00000000-0000-0000-0000-000000002614");
        UUID editionId = UUID.fromString("00000000-0000-0000-0000-000000002613");
        String editionPath = BASE_URL + "/api/v1/workspaces/" + TEAM_ID + "/seasons/" + SEASON_ID + "/editions/" + editionId;
        String freshness = """
                {"editionId":"%s","upToDate":false,"ruleVersionChanged":true,"evaluatedAt":"2026-08-31T00:00:00Z"}
                """.formatted(editionId);
        server.expect(requestTo(editionPath + "/changes?fromEditionId=" + baseId)).andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo(editionPath + "/freshness"))
                .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body(freshness));
        // 규칙 버전이 바뀐 요약을 최신이라고 하면 BRIEF 계약 위반이다.
        server.expect(requestTo(editionPath + "/freshness")).andRespond(withStatus(HttpStatus.OK)
                .contentType(MediaType.APPLICATION_JSON).body(freshness.replace("\"upToDate\":false", "\"upToDate\":true")));
        server.expect(requestTo(editionPath + "/freshness")).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> client.compareEditions(TEAM_ID, SEASON_ID, baseId, editionId))
                .isInstanceOf(BriefEditionNotFoundException.class);
        var result = client.checkEditionFreshness(TEAM_ID, SEASON_ID, editionId);
        assertThat(result.upToDate()).isFalse();
        assertThat(result.ruleVersionChanged()).isTrue();
        assertThatThrownBy(() -> client.checkEditionFreshness(TEAM_ID, SEASON_ID, editionId))
                .isInstanceOf(BriefIntegrationConfigurationException.class);
        assertThatThrownBy(() -> client.checkEditionFreshness(TEAM_ID, SEASON_ID, editionId))
                .isInstanceOf(BriefEditionNotFoundException.class);
        server.verify();
    }

    @DisplayName("BATON이 계산한 주차와 시간대만 기존 BRIEF 생성 명령에 보낸다")
    @ParameterizedTest
    @ValueSource(ints = {200, 201})
    void sendsAuthoritativeEditionCommand(int status) {
        String path = "/api/v1/workspaces/" + TEAM_ID
                + "/seasons/" + SEASON_ID + "/editions";
        server.expect(requestTo(BASE_URL + path))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + BEARER_TOKEN))
                .andExpect(content().json(
                        """
                        {
                          "weekStart": "2026-08-24",
                          "zoneId": "Asia/Seoul"
                        }
                        """,
                        JsonCompareMode.STRICT
                ))
                .andRespond(withStatus(HttpStatus.valueOf(status))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.ETAG, "\"brief-edition-v1-test\"")
                        .body(editionJson()));

        var result = client.generateEdition(
                TEAM_ID,
                SEASON_ID,
                LocalDate.parse("2026-08-24"),
                ZoneId.of("Asia/Seoul")
        );

        assertThat(result.outcome()).isEqualTo(Outcome.COMPLETED);
        assertThat(result.created()).isEqualTo(status == 201);
        server.verify();
    }

    @DisplayName("BRIEF 인증 실패와 서버 실패를 설정 실패와 재시도 실패로 구분한다")
    @Test
    void classifiesAuthenticationAndServerFailure() {
        server.expect(requestTo(BASE_URL + latestPath()))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        server.expect(requestTo(BASE_URL + latestPath()))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        var authenticationFailure = client.findLatestEdition(TEAM_ID, SEASON_ID);
        assertThat(authenticationFailure.outcome()).isEqualTo(Outcome.PERMANENT_FAILURE);
        assertThat(authenticationFailure.code()).isEqualTo("HTTP_401");
        assertThat(client.findLatestEdition(TEAM_ID, SEASON_ID).outcome())
                .isEqualTo(Outcome.RETRYABLE_FAILURE);
        server.verify();
    }

    @DisplayName("BRIEF 조회 없음과 생성 요청의 400·404 실패를 구분한다")
    @ParameterizedTest
    @ValueSource(ints = {400, 404})
    void classifiesNotFoundAndInvalidGenerationRequest(int generationStatus) {
        String generationPath = "/api/v1/workspaces/" + TEAM_ID
                + "/seasons/" + SEASON_ID + "/editions";
        server.expect(requestTo(BASE_URL + latestPath()))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo(BASE_URL + generationPath))
                .andRespond(withStatus(HttpStatus.valueOf(generationStatus)));

        assertThat(client.findLatestEdition(TEAM_ID, SEASON_ID).outcome())
                .isEqualTo(Outcome.NOT_FOUND);
        var generationFailure = client.generateEdition(
                TEAM_ID,
                SEASON_ID,
                LocalDate.parse("2026-08-24"),
                ZoneId.of("Asia/Seoul")
        );
        assertThat(generationFailure.outcome()).isEqualTo(Outcome.PERMANENT_FAILURE);
        assertThat(generationFailure.code()).isEqualTo("HTTP_" + generationStatus);
        server.verify();
    }

    @DisplayName("BRIEF 조회·생성 성공 응답 본문을 읽는 중 시간 초과가 발생하면 재시도한다")
    @ParameterizedTest(name = "생성 요청={0}")
    @ValueSource(booleans = {false, true})
    void retriesWhenSuccessfulResponseBodyTimesOut(boolean generation) {
        String path = generation
                ? "/api/v1/workspaces/" + TEAM_ID + "/seasons/" + SEASON_ID + "/editions"
                : latestPath();
        server.expect(requestTo(BASE_URL + path)).andRespond(request -> {
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
            response.getHeaders().setETag("\"brief-edition-v1-test\"");
            return response;
        });

        var result = generation
                ? client.generateEdition(
                        TEAM_ID,
                        SEASON_ID,
                        LocalDate.parse("2026-08-24"),
                        ZoneId.of("Asia/Seoul")
                )
                : client.findLatestEdition(TEAM_ID, SEASON_ID);

        assertThat(result.outcome()).isEqualTo(Outcome.RETRYABLE_FAILURE);
        assertThat(result.code()).isEqualTo("BRIEF_NETWORK_FAILURE");
        server.verify();
    }

    @DisplayName("BRIEF가 잘못된 성공 상태로 응답하면 영구 실패로 처리한다")
    @ParameterizedTest(name = "생성 요청={0}")
    @ValueSource(booleans = {false, true})
    void rejectsUnexpectedSuccessStatus(boolean generation) {
        server.expect(requestTo(BASE_URL + (generation ? generationPath() : latestPath())))
                .andRespond(withStatus(generation ? HttpStatus.ACCEPTED : HttpStatus.CREATED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.ETAG, "\"brief-edition-v1-test\"")
                        .body(editionJson()));

        var unexpectedSuccess = generation ? client.generateEdition(
                TEAM_ID,
                SEASON_ID,
                LocalDate.parse("2026-08-24"),
                ZoneId.of("Asia/Seoul")
        ) : client.findLatestEdition(TEAM_ID, SEASON_ID);

        assertThat(unexpectedSuccess.outcome()).isEqualTo(Outcome.PERMANENT_FAILURE);
        assertThat(unexpectedSuccess.code()).isEqualTo("BRIEF_RESPONSE_INVALID");
        server.verify();
    }

    @DisplayName("BRIEF 성공 응답의 필수 필드가 없거나 JSON 형식이 잘못되면 영구 실패로 처리한다")
    @Test
    void rejectsIncompleteOrMalformedSuccessResponse() {
        server.expect(requestTo(BASE_URL + latestPath()))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.ETAG, "\"brief-edition-v1-test\"")
                        .body(editionJson().replace("\"items\": []", "\"items\": null")));
        server.expect(requestTo(BASE_URL + generationPath()))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.ETAG, "\"brief-edition-v1-test\"")
                        .body("{"));

        var incompleteResponse = client.findLatestEdition(TEAM_ID, SEASON_ID);
        var malformedResponse = client.generateEdition(
                TEAM_ID,
                SEASON_ID,
                LocalDate.parse("2026-08-24"),
                ZoneId.of("Asia/Seoul")
        );

        assertThat(incompleteResponse.outcome()).isEqualTo(Outcome.PERMANENT_FAILURE);
        assertThat(incompleteResponse.code()).isEqualTo("BRIEF_RESPONSE_INVALID");
        assertThat(malformedResponse.outcome()).isEqualTo(Outcome.PERMANENT_FAILURE);
        assertThat(malformedResponse.code()).isEqualTo("BRIEF_RESPONSE_FAILURE");
        server.verify();
    }

    private String generationPath() {
        return "/api/v1/workspaces/" + TEAM_ID + "/seasons/" + SEASON_ID + "/editions";
    }

    private String latestPath() {
        return "/api/v1/workspaces/" + TEAM_ID
                + "/seasons/" + SEASON_ID + "/editions/latest";
    }

    private String editionJson() {
        return """
                {
                  "editionId": "00000000-0000-0000-0000-000000002613",
                  "workspaceId": "%s",
                  "seasonId": "%s",
                  "generation": 3,
                  "weekStart": "2026-08-24",
                  "zoneId": "Asia/Seoul",
                  "windowStart": "2026-08-23T15:00:00Z",
                  "windowEnd": "2026-08-30T15:00:00Z",
                  "sourceCursor": 17,
                  "generatedAt": "2026-08-29T03:00:00Z",
                  "ruleVersion": 1,
                  "items": []
                }
                """.formatted(TEAM_ID, SEASON_ID);
    }
}
