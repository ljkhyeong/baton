package com.personal.baton.adapter.out.external.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.personal.baton.application.link.error.InvalidLinkIntentException;
import com.personal.baton.application.link.error.LinkGatewayConflictException;
import com.personal.baton.application.link.error.LinkGatewayUnavailableException;
import com.personal.baton.application.link.port.out.RoleResourceLinkPort.LinkNavigation;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class BatonGoRoleResourceLinkAdapterTest {

    private static final String MANAGEMENT_TOKEN =
            "go-management-token-with-at-least-32-characters";
    private static final UUID IDEMPOTENCY_KEY =
            UUID.fromString("a4444444-4444-4444-8444-444444444444");
    private static final Instant EXPIRES_AT = Instant.parse("2026-07-30T12:10:00Z");
    private static final URI ROUND_ROOM =
            URI.create("https://round.example/room/abcd-efgh-jkmn");

    @Test
    @DisplayName("비활성화된 연동은 모든 자료 URL을 원본 주소로 직접 이동시킨다")
    void returnsDirectNavigationWhenDisabled() {
        BatonGoProperties properties = new BatonGoProperties();
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        BatonGoRoleResourceLinkAdapter adapter =
                adapter(properties, builder.build());

        LinkNavigation result = adapter.createNavigation(
                ROUND_ROOM,
                IDEMPOTENCY_KEY,
                EXPIRES_AT
        );

        assertThat(result.navigationUrl()).isEqualTo(ROUND_ROOM);
        assertThat(result.managedByBatonGo()).isFalse();
        assertThat(result.expiresAt()).isNull();
        server.verify();
    }

    @Test
    @DisplayName("신뢰된 ROUND 회의 경로만 자격 증명 없는 GO 생성 요청으로 전송한다")
    void createsBatonGoLinkForTrustedRoundRoom() {
        BatonGoProperties properties = enabledProperties("https://go.internal.example");
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        BatonGoRoleResourceLinkAdapter adapter =
                adapter(properties, builder.build());
        server.expect(requestTo("https://go.internal.example/api/v1/links"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + MANAGEMENT_TOKEN))
                .andExpect(header("Idempotency-Key", IDEMPOTENCY_KEY.toString()))
                .andExpect(headerDoesNotExist("X-Baton-Access-Key"))
                .andExpect(content().json("""
                        {
                          "targetSystem": "ROUND",
                          "targetPath": "/room/abcd-efgh-jkmn",
                          "purpose": "MEETING_ENTRY",
                          "expiresAt": "2026-07-30T12:10:00Z"
                        }
                        """))
                .andRespond(withStatus(HttpStatus.CREATED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {
                                  "id": "66666666-6666-4666-8666-666666666666",
                                  "shortUrl": "https://go.example/l/VOvLShvx93kQpj8x7w2HYQ",
                                  "targetSystem": "ROUND",
                                  "targetPath": "/room/abcd-efgh-jkmn",
                                  "purpose": "MEETING_ENTRY",
                                  "notBefore": null,
                                  "expiresAt": "2026-07-30T12:10:00Z",
                                  "createdAt": "2026-07-30T12:00:00Z",
                                  "revokedAt": null
                                }
                                """));

        LinkNavigation result = adapter.createNavigation(
                ROUND_ROOM,
                IDEMPOTENCY_KEY,
                EXPIRES_AT
        );

        assertThat(result.navigationUrl())
                .isEqualTo(URI.create("https://go.example/l/VOvLShvx93kQpj8x7w2HYQ"));
        assertThat(result.managedByBatonGo()).isTrue();
        assertThat(result.expiresAt()).isEqualTo(EXPIRES_AT);
        server.verify();
    }

    @Test
    @DisplayName("멱등 재생의 200 응답도 동일한 GO 링크로 처리한다")
    void acceptsReplayResponse() {
        BatonGoProperties properties = enabledProperties("https://go.example");
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        BatonGoRoleResourceLinkAdapter adapter =
                adapter(properties, builder.build());
        server.expect(requestTo("https://go.example/api/v1/links"))
                .andRespond(withSuccess(
                        """
                                {
                                  "shortUrl": "https://go.example/l/abcdefghijklmnopqrstuv",
                                  "targetSystem": "ROUND",
                                  "targetPath": "/room/abcd-efgh-jkmn",
                                  "purpose": "MEETING_ENTRY",
                                  "notBefore": null,
                                  "expiresAt": "2026-07-30T12:10:00Z",
                                  "revokedAt": null
                                }
                                """,
                        MediaType.APPLICATION_JSON
                ));

        LinkNavigation result = adapter.createNavigation(
                ROUND_ROOM,
                IDEMPOTENCY_KEY,
                EXPIRES_AT
        );

        assertThat(result.navigationUrl())
                .isEqualTo(URI.create("https://go.example/l/abcdefghijklmnopqrstuv"));
        assertThat(result.managedByBatonGo()).isTrue();
        server.verify();
    }

    @Test
    @DisplayName("대소문자와 기본 포트 표기가 달라도 같은 GO와 ROUND origin으로 처리한다")
    void acceptsEquivalentOriginRepresentations() {
        BatonGoProperties properties = enabledProperties("https://go.example");
        properties.setPublicBaseUrl(URI.create("https://go.example"));
        properties.setRoundPublicBaseUrl(URI.create("HTTPS://ROUND.EXAMPLE:443/"));
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        BatonGoRoleResourceLinkAdapter adapter = adapter(properties, builder.build());
        server.expect(requestTo("https://go.example/api/v1/links"))
                .andRespond(withSuccess(
                        """
                                {
                                  "shortUrl": "HTTPS://GO.EXAMPLE:443/l/abcdefghijklmnopqrstuv",
                                  "targetSystem": "ROUND",
                                  "targetPath": "/room/abcd-efgh-jkmn",
                                  "purpose": "MEETING_ENTRY",
                                  "notBefore": null,
                                  "expiresAt": "2026-07-30T12:10:00Z",
                                  "revokedAt": null
                                }
                                """,
                        MediaType.APPLICATION_JSON
                ));

        LinkNavigation result = adapter.createNavigation(
                ROUND_ROOM,
                IDEMPOTENCY_KEY,
                EXPIRES_AT
        );

        assertThat(result.managedByBatonGo()).isTrue();
        assertThat(result.navigationUrl())
                .isEqualTo(URI.create(
                        "HTTPS://GO.EXAMPLE:443/l/abcdefghijklmnopqrstuv"
                ));
        server.verify();
    }

    @Test
    @DisplayName("ROUND가 아닌 일반 URL은 GO를 호출하지 않고 직접 이동시킨다")
    void neverCallsBatonGoForOrdinaryExternalUrls() {
        BatonGoProperties properties = enabledProperties("https://go.example");
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        BatonGoRoleResourceLinkAdapter adapter =
                adapter(properties, builder.build());
        List<URI> directUrls = List.of(
                URI.create("https://docs.example/guide"),
                URI.create("https://evil.example/room/abcd-efgh-jkmn")
        );

        for (URI directUrl : directUrls) {
            LinkNavigation result = adapter.createNavigation(
                    directUrl,
                    IDEMPOTENCY_KEY,
                    EXPIRES_AT
            );

            assertThat(result.navigationUrl()).isEqualTo(directUrl);
            assertThat(result.managedByBatonGo()).isFalse();
            assertThat(result.expiresAt()).isNull();
        }
        server.verify();
    }

    @Test
    @DisplayName("ROUND origin의 비정규 URL은 직접 이동으로 우회하지 않고 거절한다")
    void rejectsNonCanonicalRoundUrlsWithoutDirectFallback() {
        BatonGoProperties properties = enabledProperties("https://go.example");
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        BatonGoRoleResourceLinkAdapter adapter =
                adapter(properties, builder.build());

        for (URI unsafeRoundUrl : List.of(
                URI.create("https://round.example/room/abcd-efgh-jkmn?ticket=secret"),
                URI.create("https://round.example/room/ABCD-EFGH-JKMN"),
                URI.create("https://round.example/not-a-room")
        )) {
            assertThatThrownBy(() -> adapter.createNavigation(
                    unsafeRoundUrl,
                    IDEMPOTENCY_KEY,
                    EXPIRES_AT
            ))
                    .isInstanceOf(InvalidLinkIntentException.class)
                    .extracting(exception ->
                            ((InvalidLinkIntentException) exception).getCode())
                    .isEqualTo("INVALID_ROUND_RESOURCE_URL");
        }
        server.verify();
    }

    @Test
    @DisplayName("GO의 멱등 충돌은 구분 가능한 gateway 충돌로 변환한다")
    void mapsConflictResponse() {
        BatonGoProperties properties = enabledProperties("https://go.example");
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        BatonGoRoleResourceLinkAdapter adapter =
                adapter(properties, builder.build());
        server.expect(requestTo("https://go.example/api/v1/links"))
                .andRespond(withStatus(HttpStatus.CONFLICT));

        assertThatThrownBy(() -> adapter.createNavigation(
                ROUND_ROOM,
                IDEMPOTENCY_KEY,
                EXPIRES_AT
        )).isInstanceOf(LinkGatewayConflictException.class);
        server.verify();
    }

    @Test
    @DisplayName("GO 오류와 안전하지 않은 short URL은 gateway 가용성 오류로 변환한다")
    void mapsUpstreamErrorsAndUnsafeResponses() {
        BatonGoProperties properties = enabledProperties("https://go.example");
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        BatonGoRoleResourceLinkAdapter adapter =
                adapter(properties, builder.build());
        server.expect(requestTo("https://go.example/api/v1/links"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> adapter.createNavigation(
                ROUND_ROOM,
                IDEMPOTENCY_KEY,
                EXPIRES_AT
        )).isInstanceOf(LinkGatewayUnavailableException.class);
        server.verify();

        for (String unsafeShortUrl : List.of(
                "https://user:password@go.example/l/abcdefghijklmnopqrstuv",
                "https://evil.example/l/abcdefghijklmnopqrstuv",
                "https://go.example/l/abcdefghijklmnopqrstuv?ticket=secret",
                "https://go.example/l/too-short"
        )) {
            assertUnavailableResponse(properties, """
                    {
                      "shortUrl": "%s",
                      "targetSystem": "ROUND",
                      "targetPath": "/room/abcd-efgh-jkmn",
                      "purpose": "MEETING_ENTRY",
                      "notBefore": null,
                      "expiresAt": "2026-07-30T12:10:00Z",
                      "revokedAt": null
                    }
                    """.formatted(unsafeShortUrl));
        }
    }

    @Test
    @DisplayName("GO 응답이 요청한 대상이나 수명주기와 다르면 navigation으로 신뢰하지 않는다")
    void rejectsMismatchedBatonGoResponses() {
        BatonGoProperties properties = enabledProperties("https://go.example");
        for (String response : List.of(
                """
                {
                  "shortUrl": "https://go.example/l/abcdefghijklmnopqrstuv",
                  "targetSystem": "BATON",
                  "targetPath": "/room/abcd-efgh-jkmn",
                  "purpose": "MEETING_ENTRY",
                  "notBefore": null,
                  "expiresAt": "2026-07-30T12:10:00Z",
                  "revokedAt": null
                }
                """,
                """
                {
                  "shortUrl": "https://go.example/l/abcdefghijklmnopqrstuv",
                  "targetSystem": "ROUND",
                  "targetPath": "/room/wxyz-2345-6789",
                  "purpose": "MEETING_ENTRY",
                  "notBefore": null,
                  "expiresAt": "2026-07-30T12:10:00Z",
                  "revokedAt": null
                }
                """,
                """
                {
                  "shortUrl": "https://go.example/l/abcdefghijklmnopqrstuv",
                  "targetSystem": "ROUND",
                  "targetPath": "/room/abcd-efgh-jkmn",
                  "purpose": "NAVIGATION",
                  "notBefore": null,
                  "expiresAt": "2026-07-30T12:10:00Z",
                  "revokedAt": null
                }
                """,
                """
                {
                  "shortUrl": "https://go.example/l/abcdefghijklmnopqrstuv",
                  "targetSystem": "ROUND",
                  "targetPath": "/room/abcd-efgh-jkmn",
                  "purpose": "MEETING_ENTRY",
                  "notBefore": null,
                  "expiresAt": "2026-07-30T12:11:00Z",
                  "revokedAt": null
                }
                """,
                """
                {
                  "shortUrl": "https://go.example/l/abcdefghijklmnopqrstuv",
                  "targetSystem": "ROUND",
                  "targetPath": "/room/abcd-efgh-jkmn",
                  "purpose": "MEETING_ENTRY",
                  "notBefore": null,
                  "expiresAt": "2026-07-30T12:10:00Z",
                  "revokedAt": "2026-07-30T12:01:00Z"
                }
                """
        )) {
            assertUnavailableResponse(properties, response);
        }
    }

    private BatonGoProperties enabledProperties(String baseUrl) {
        BatonGoProperties properties = new BatonGoProperties();
        properties.setEnabled(true);
        properties.setBaseUrl(URI.create(baseUrl));
        properties.setPublicBaseUrl(URI.create("https://go.example"));
        properties.setManagementToken(MANAGEMENT_TOKEN);
        properties.setRoundPublicBaseUrl(URI.create("https://round.example"));
        properties.setConnectTimeout(Duration.ofMillis(200));
        properties.setReadTimeout(Duration.ofMillis(500));
        return properties;
    }

    private void assertUnavailableResponse(
            BatonGoProperties properties,
            String responseBody
    ) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        BatonGoRoleResourceLinkAdapter adapter =
                adapter(properties, builder.build());
        server.expect(requestTo("https://go.example/api/v1/links"))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.createNavigation(
                ROUND_ROOM,
                IDEMPOTENCY_KEY,
                EXPIRES_AT
        )).isInstanceOf(LinkGatewayUnavailableException.class);
        server.verify();
    }

    private BatonGoRoleResourceLinkAdapter adapter(
            BatonGoProperties properties,
            RestClient restClient
    ) {
        return new BatonGoRoleResourceLinkAdapter(BatonGoSettings.from(properties), restClient);
    }
}
