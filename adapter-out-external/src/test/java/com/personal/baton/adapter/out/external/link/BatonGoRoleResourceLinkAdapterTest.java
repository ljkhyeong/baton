package com.personal.baton.adapter.out.external.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
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
import com.sun.net.httpserver.HttpServer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.http.client.HttpRedirects;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
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
                new BatonGoRoleResourceLinkAdapter(properties, builder.build());

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
                new BatonGoRoleResourceLinkAdapter(properties, builder.build());
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
                new BatonGoRoleResourceLinkAdapter(properties, builder.build());
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
    @DisplayName("ROUND가 아닌 일반 URL은 GO를 호출하지 않고 직접 이동시킨다")
    void neverCallsBatonGoForOrdinaryExternalUrls() {
        BatonGoProperties properties = enabledProperties("https://go.example");
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        BatonGoRoleResourceLinkAdapter adapter =
                new BatonGoRoleResourceLinkAdapter(properties, builder.build());
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
                new BatonGoRoleResourceLinkAdapter(properties, builder.build());

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
                new BatonGoRoleResourceLinkAdapter(properties, builder.build());
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
                new BatonGoRoleResourceLinkAdapter(properties, builder.build());
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

    @Test
    @DisplayName("GO 읽기 제한 시간을 넘긴 응답은 gateway 가용성 오류로 변환한다")
    void mapsReadTimeout() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/links", exchange -> {
            try {
                Thread.sleep(250);
                byte[] response = """
                        {"shortUrl":"https://go.example/l/late-code"}
                        """.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set(
                        HttpHeaders.CONTENT_TYPE,
                        MediaType.APPLICATION_JSON_VALUE
                );
                exchange.sendResponseHeaders(HttpStatus.CREATED.value(), response.length);
                exchange.getResponseBody().write(response);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            BatonGoProperties properties = enabledProperties(
                    "http://127.0.0.1:" + server.getAddress().getPort()
            );
            properties.setReadTimeout(Duration.ofMillis(30));
            BatonGoRoleResourceLinkAdapter adapter =
                    new BatonGoRoleResourceLinkAdapter(
                            properties,
                            RestClient.builder(),
                            ClientHttpRequestFactoryBuilder.jdk(),
                            HttpClientSettings.defaults()
                    );

            assertThatThrownBy(() -> adapter.createNavigation(
                    ROUND_ROOM,
                    IDEMPOTENCY_KEY,
                    EXPIRES_AT
            )).isInstanceOf(LinkGatewayUnavailableException.class);
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("GO 외부 호출은 upstream redirect를 따라가지 않는다")
    void neverFollowsUpstreamRedirects() throws IOException {
        AtomicBoolean redirected = new AtomicBoolean();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/links", exchange -> {
            exchange.getResponseHeaders().set(HttpHeaders.LOCATION, "/redirected");
            exchange.sendResponseHeaders(HttpStatus.FOUND.value(), -1);
            exchange.close();
        });
        server.createContext("/redirected", exchange -> {
            redirected.set(true);
            exchange.sendResponseHeaders(HttpStatus.NO_CONTENT.value(), -1);
            exchange.close();
        });
        server.start();
        try {
            BatonGoProperties properties = enabledProperties(
                    "http://127.0.0.1:" + server.getAddress().getPort()
            );
            BatonGoRoleResourceLinkAdapter adapter =
                    new BatonGoRoleResourceLinkAdapter(
                            properties,
                            RestClient.builder(),
                            ClientHttpRequestFactoryBuilder.jdk(),
                            HttpClientSettings.defaults()
                    );

            assertThatThrownBy(() -> adapter.createNavigation(
                    ROUND_ROOM,
                    IDEMPOTENCY_KEY,
                    EXPIRES_AT
            )).isInstanceOf(LinkGatewayUnavailableException.class);
            assertThat(redirected).isFalse();
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("주입된 RestClient 관측 설정은 GO 외부 호출까지 유지된다")
    void preservesInjectedRestClientObservation() throws IOException {
        AtomicBoolean observed = new AtomicBoolean();
        ObservationRegistry observationRegistry = ObservationRegistry.create();
        observationRegistry.observationConfig().observationHandler(
                new ObservationHandler<Observation.Context>() {
                    @Override
                    public void onStop(Observation.Context context) {
                        if ("http.client.requests".equals(context.getName())) {
                            observed.set(true);
                        }
                    }

                    @Override
                    public boolean supportsContext(Observation.Context context) {
                        return true;
                    }
                }
        );

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/links", exchange -> {
            byte[] response = """
                    {
                      "shortUrl": "https://go.example/l/abcdefghijklmnopqrstuv",
                      "targetSystem": "ROUND",
                      "targetPath": "/room/abcd-efgh-jkmn",
                      "purpose": "MEETING_ENTRY",
                      "notBefore": null,
                      "expiresAt": "2026-07-30T12:10:00Z",
                      "revokedAt": null
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set(
                    HttpHeaders.CONTENT_TYPE,
                    MediaType.APPLICATION_JSON_VALUE
            );
            exchange.sendResponseHeaders(HttpStatus.CREATED.value(), response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            BatonGoProperties properties = enabledProperties(
                    "http://127.0.0.1:" + server.getAddress().getPort()
            );
            RestClient.Builder builder = RestClient.builder()
                    .observationRegistry(observationRegistry);
            BatonGoRoleResourceLinkAdapter adapter =
                    new BatonGoRoleResourceLinkAdapter(
                            properties,
                            builder,
                            ClientHttpRequestFactoryBuilder.jdk(),
                            HttpClientSettings.defaults()
                    );

            LinkNavigation result = adapter.createNavigation(
                    ROUND_ROOM,
                    IDEMPOTENCY_KEY,
                    EXPIRES_AT
            );

            assertThat(result.managedByBatonGo()).isTrue();
            assertThat(observed).isTrue();
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("GO HTTP 설정은 전역 SSL을 유지하고 전용 제한 시간을 적용한다")
    void appliesBatonGoSettingsOnTopOfGlobalHttpSettings() {
        AtomicReference<HttpClientSettings> capturedSettings = new AtomicReference<>();
        ClientHttpRequestFactoryBuilder<SimpleClientHttpRequestFactory> capturingBuilder =
                settings -> {
                    capturedSettings.set(settings);
                    return new SimpleClientHttpRequestFactory();
                };
        SslBundle sslBundle = mock(SslBundle.class);
        HttpClientSettings globalSettings = HttpClientSettings.defaults()
                .withConnectTimeout(Duration.ofSeconds(10))
                .withReadTimeout(Duration.ofSeconds(20))
                .withRedirects(HttpRedirects.FOLLOW)
                .withSslBundle(sslBundle);
        BatonGoProperties properties = enabledProperties("https://go.internal.example");

        new BatonGoRoleResourceLinkAdapter(
                properties,
                RestClient.builder(),
                capturingBuilder,
                globalSettings
        );

        assertThat(capturedSettings.get().connectTimeout())
                .isEqualTo(properties.getConnectTimeout());
        assertThat(capturedSettings.get().readTimeout())
                .isEqualTo(properties.getReadTimeout());
        assertThat(capturedSettings.get().redirects())
                .isEqualTo(HttpRedirects.DONT_FOLLOW);
        assertThat(capturedSettings.get().sslBundle()).isSameAs(sslBundle);
    }

    @Test
    @DisplayName("활성화된 GO 연동의 필수 설정이 없으면 시작 구성을 거부한다")
    void rejectsInvalidEnabledConfiguration() {
        BatonGoProperties properties = new BatonGoProperties();
        properties.setEnabled(true);
        properties.setBaseUrl(URI.create("https://go.example"));
        properties.setPublicBaseUrl(URI.create("https://go.example"));
        properties.setRoundPublicBaseUrl(URI.create("https://round.example"));

        assertThatThrownBy(() -> new BatonGoRoleResourceLinkAdapter(
                properties,
                RestClient.builder(),
                ClientHttpRequestFactoryBuilder.jdk(),
                HttpClientSettings.defaults()
        ))
                .isInstanceOf(LinkGatewayUnavailableException.class);
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
                new BatonGoRoleResourceLinkAdapter(properties, builder.build());
        server.expect(requestTo("https://go.example/api/v1/links"))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.createNavigation(
                ROUND_ROOM,
                IDEMPOTENCY_KEY,
                EXPIRES_AT
        )).isInstanceOf(LinkGatewayUnavailableException.class);
        server.verify();
    }
}
