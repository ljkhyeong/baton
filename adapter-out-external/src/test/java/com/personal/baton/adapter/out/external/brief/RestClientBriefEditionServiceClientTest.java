package com.personal.baton.adapter.out.external.brief;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import com.personal.baton.application.brief.port.out.BriefEditionServiceClient.Outcome;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
