package com.personal.baton.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.personal.baton.adapter.out.external.link.BatonGoProperties;
import com.personal.baton.adapter.out.external.link.BatonGoRoleResourceLinkAdapter;
import com.personal.baton.adapter.out.external.link.BatonGoSettings;
import com.personal.baton.application.link.error.LinkGatewayUnavailableException;
import com.personal.baton.application.link.port.out.RoleResourceLinkPort;
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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.http.client.HttpRedirects;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.http.client.autoconfigure.imperative.ImperativeHttpClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

class BatonGoClientConfigTest {

    private static final String MANAGEMENT_TOKEN =
            "go-management-token-with-at-least-32-characters";
    private static final UUID IDEMPOTENCY_KEY =
            UUID.fromString("a4444444-4444-4444-8444-444444444444");
    private static final Instant EXPIRES_AT = Instant.parse("2026-07-30T12:10:00Z");
    private static final URI ROUND_ROOM =
            URI.create("https://round.example/room/abcd-efgh-jkmn");

    private final BatonGoClientConfig config = new BatonGoClientConfig();
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    HttpClientAutoConfiguration.class,
                    ImperativeHttpClientAutoConfiguration.class,
                    RestClientAutoConfiguration.class
            ))
            .withUserConfiguration(BatonGoClientConfig.class);

    @Test
    @DisplayName("비활성화된 GO 연동은 credential 없이도 시작 구성을 완료한다")
    void startsDisabledIntegrationWithoutCredentials() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(BatonGoSettings.class);
            assertThat(context).hasSingleBean(RoleResourceLinkPort.class);
            assertThat(context).hasBean(BatonGoClientConfig.BATON_GO_REST_CLIENT);
            assertThat(context.getBean(BatonGoSettings.class).enabled()).isFalse();
            assertThat(context.getBean(BatonGoProperties.class).getReadTimeout())
                    .isEqualTo(Duration.ofSeconds(2));
        });
    }

    @Test
    @DisplayName("활성화된 GO 연동은 필수 credential이 없으면 시작을 거절한다")
    void rejectsMissingManagementCredential() {
        contextRunner
                .withPropertyValues(
                        "baton.integrations.go.enabled=true",
                        "baton.integrations.go.base-url=https://go.internal.example",
                        "baton.integrations.go.public-base-url=https://go.example",
                        "baton.integrations.go.round-public-base-url=https://round.example"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage("management-token은 32자 이상이어야 합니다");
                });
    }

    @Test
    @DisplayName("활성화된 GO 연동은 origin이 아닌 관리 주소를 거절한다")
    void rejectsManagementUrlWithPath() {
        contextRunner
                .withPropertyValues(
                        "baton.integrations.go.enabled=true",
                        "baton.integrations.go.base-url=https://go.internal.example/api",
                        "baton.integrations.go.public-base-url=https://go.example",
                        "baton.integrations.go.management-token=" + MANAGEMENT_TOKEN,
                        "baton.integrations.go.round-public-base-url=https://round.example"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage(
                                    "base-url 설정은 http(s) origin이어야 합니다"
                            );
                });
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
            BatonGoRoleResourceLinkAdapter adapter = configuredAdapter(
                    properties,
                    RestClient.builder()
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
            BatonGoRoleResourceLinkAdapter adapter = configuredAdapter(
                    properties,
                    RestClient.builder()
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
            BatonGoRoleResourceLinkAdapter adapter = configuredAdapter(properties, builder);

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
        BatonGoSettings settings = config.batonGoSettings(properties);

        config.batonGoRestClient(
                settings,
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

    private BatonGoRoleResourceLinkAdapter configuredAdapter(
            BatonGoProperties properties,
            RestClient.Builder restClientBuilder
    ) {
        BatonGoSettings settings = config.batonGoSettings(properties);
        RestClient restClient = config.batonGoRestClient(
                settings,
                restClientBuilder,
                ClientHttpRequestFactoryBuilder.jdk(),
                HttpClientSettings.defaults()
        );
        return config.batonGoRoleResourceLinkAdapter(settings, restClient);
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
}
