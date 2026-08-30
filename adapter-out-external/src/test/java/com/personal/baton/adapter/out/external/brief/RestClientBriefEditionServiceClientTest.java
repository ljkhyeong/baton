package com.personal.baton.adapter.out.external.brief;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import com.personal.baton.application.brief.port.out.BriefEditionServiceClient.Outcome;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
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

class RestClientBriefEditionServiceClientTest {

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
    private RestClientBriefEditionServiceClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(BASE_URL)
                .defaultHeaders(headers -> headers.setBearerAuth(BEARER_TOKEN));
        server = MockRestServiceServer.bindTo(builder).build();
        client = new RestClientBriefEditionServiceClient(builder.build());
    }

    @DisplayName("별도 Bearer로 최신 에디션을 조회하고 ETag를 보존한다")
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

    @DisplayName("BATON이 계산한 주차와 시간대만 기존 BRIEF 생성 명령에 보낸다")
    @Test
    void sendsAuthoritativeEditionCommand() {
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
                .andRespond(withStatus(HttpStatus.CREATED)
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
        assertThat(result.created()).isTrue();
        server.verify();
    }

    @DisplayName("BRIEF 인증 실패와 서버 실패를 설정 실패와 재시도 실패로 구분한다")
    @Test
    void classifiesAuthenticationAndServerFailure() {
        server.expect(requestTo(BASE_URL + latestPath()))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        server.expect(requestTo(BASE_URL + latestPath()))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThat(client.findLatestEdition(TEAM_ID, SEASON_ID).outcome())
                .isEqualTo(Outcome.AUTHENTICATION_FAILURE);
        assertThat(client.findLatestEdition(TEAM_ID, SEASON_ID).outcome())
                .isEqualTo(Outcome.RETRYABLE_FAILURE);
        server.verify();
    }

    @DisplayName("BRIEF 조회 없음과 잘못된 생성 요청을 계약 결과로 구분한다")
    @Test
    void classifiesNotFoundAndInvalidGenerationRequest() {
        String generationPath = "/api/v1/workspaces/" + TEAM_ID
                + "/seasons/" + SEASON_ID + "/editions";
        server.expect(requestTo(BASE_URL + latestPath()))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo(BASE_URL + generationPath))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertThat(client.findLatestEdition(TEAM_ID, SEASON_ID).outcome())
                .isEqualTo(Outcome.NOT_FOUND);
        assertThat(client.generateEdition(
                TEAM_ID,
                SEASON_ID,
                LocalDate.parse("2026-08-24"),
                ZoneId.of("Asia/Seoul")
        ).outcome()).isEqualTo(Outcome.INVALID_REQUEST);
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

    @DisplayName("BRIEF가 잘못된 성공 상태·필수 필드·JSON 형식으로 응답하면 영구 실패로 처리한다")
    @Test
    void rejectsUnexpectedSuccessAndIncompleteResponse() {
        String generationPath = "/api/v1/workspaces/" + TEAM_ID
                + "/seasons/" + SEASON_ID + "/editions";
        server.expect(requestTo(BASE_URL + generationPath))
                .andRespond(withStatus(HttpStatus.ACCEPTED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.ETAG, "\"brief-edition-v1-test\"")
                        .body(editionJson()));
        server.expect(requestTo(BASE_URL + latestPath()))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.ETAG, "\"brief-edition-v1-test\"")
                        .body(editionJson().replace("\"items\": []", "\"items\": null")));
        server.expect(requestTo(BASE_URL + generationPath))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.ETAG, "\"brief-edition-v1-test\"")
                        .body("{"));

        var unexpectedSuccess = client.generateEdition(
                TEAM_ID,
                SEASON_ID,
                LocalDate.parse("2026-08-24"),
                ZoneId.of("Asia/Seoul")
        );
        var incompleteResponse = client.findLatestEdition(TEAM_ID, SEASON_ID);
        var malformedResponse = client.generateEdition(
                TEAM_ID,
                SEASON_ID,
                LocalDate.parse("2026-08-24"),
                ZoneId.of("Asia/Seoul")
        );

        assertThat(unexpectedSuccess.outcome()).isEqualTo(Outcome.PERMANENT_FAILURE);
        assertThat(unexpectedSuccess.code()).isEqualTo("BRIEF_RESPONSE_INVALID");
        assertThat(incompleteResponse.outcome()).isEqualTo(Outcome.PERMANENT_FAILURE);
        assertThat(incompleteResponse.code()).isEqualTo("BRIEF_RESPONSE_INVALID");
        assertThat(malformedResponse.outcome()).isEqualTo(Outcome.PERMANENT_FAILURE);
        assertThat(malformedResponse.code()).isEqualTo("BRIEF_RESPONSE_FAILURE");
        server.verify();
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
