package com.personal.baton.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.personal.baton.adapter.out.external.watch.DisabledWatchMonitorClient;
import com.personal.baton.adapter.out.external.watch.RestClientWatchMonitorClient;
import com.personal.baton.application.watch.WatchMonitorDelivery;
import com.personal.baton.application.watch.WatchMonitorSource;
import com.personal.baton.application.watch.port.out.WatchMonitorClient;
import com.personal.baton.application.watch.port.out.WatchMonitorClient.Outcome;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class WatchIntegrationConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(
                    RestClientWatchMonitorClient.Factory.class,
                    WatchIntegrationConfigTest::watchClientFactory
            )
            .withUserConfiguration(WatchIntegrationConfig.class);

    private static RestClientWatchMonitorClient.Factory watchClientFactory() {
        RestClientWatchMonitorClient.Factory factory = mock(RestClientWatchMonitorClient.Factory.class);
        when(factory.create(
                any(URI.class),
                anyString(),
                any(Duration.class),
                any(Duration.class)
        )).thenReturn(mock(RestClientWatchMonitorClient.class));
        return factory;
    }

    @Test
    @DisplayName("WATCH 연동은 기본 비활성 상태이며 외부 설정 없이 애플리케이션을 시작한다")
    void startWithDisabledClientByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(WatchMonitorClient.class);
            WatchMonitorClient client = context.getBean(WatchMonitorClient.class);
            assertThat(client).isInstanceOf(DisabledWatchMonitorClient.class);
            var result = client.synchronize(mock(WatchMonitorDelivery.class));
            assertThat(result.outcome()).isEqualTo(Outcome.RETRYABLE_FAILURE);
            assertThat(result.code()).isEqualTo("WATCH_DISABLED");
            WatchMonitorSource source = context.getBean(WatchMonitorSource.class);
            assertThat(source.namespace()).isEqualTo("primary");
            assertThat(source.enabled()).isFalse();
            assertThat(source.monitoringEnabled()).isTrue();
        });
    }

    @Test
    @DisplayName("WATCH 연동을 켜면 유효한 URL과 충분히 긴 토큰으로 HTTP 클라이언트를 구성한다")
    void createEnabledClientWithValidConfiguration() {
        contextRunner
                .withPropertyValues(
                        "baton.watch.enabled=true",
                        "baton.watch.base-url=https://watch.internal",
                        "baton.watch.bearer-token=watch-token-with-at-least-32-characters",
                        "baton.watch.source-namespace=study-pilot",
                        "baton.watch.connect-timeout=PT1S",
                        "baton.watch.read-timeout=PT3S"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(WatchMonitorClient.class);
                    assertThat(context.getBean(WatchMonitorClient.class))
                            .isInstanceOf(RestClientWatchMonitorClient.class);
                    WatchMonitorSource source = context.getBean(WatchMonitorSource.class);
                    assertThat(source.namespace()).isEqualTo("study-pilot");
                    assertThat(source.enabled()).isTrue();
                    assertThat(source.monitoringEnabled()).isTrue();
                });
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 65_535})
    @DisplayName("WATCH 기본 URL의 명시 포트는 유효 범위 경곗값을 허용한다")
    void acceptValidExplicitPortBoundaries(int port) {
        contextRunner
                .withPropertyValues(
                        "baton.watch.enabled=true",
                        "baton.watch.base-url=https://watch.internal:" + port,
                        "baton.watch.bearer-token=watch-token-with-at-least-32-characters",
                        "baton.watch.source-namespace=study-pilot"
                )
                .run(context -> assertThat(context).hasNotFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://watch.internal:0",
            "https://watch.internal:65536",
            "https://watch.internal:"
    })
    @DisplayName("WATCH 기본 URL의 명시 포트가 유효 범위 밖이면 시작을 거부한다")
    void rejectInvalidExplicitPortBoundaries(String baseUrl) {
        contextRunner
                .withPropertyValues(
                        "baton.watch.enabled=true",
                        "baton.watch.base-url=" + baseUrl,
                        "baton.watch.bearer-token=watch-token-with-at-least-32-characters",
                        "baton.watch.source-namespace=study-pilot"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage(
                            "WATCH 기본 URL의 명시 포트는 1~65535 범위여야 합니다"
                    );
                });
    }

    @Test
    @DisplayName("WATCH 점검 중단 모드는 전송 연결을 유지하면서 desired state를 비활성화한다")
    void keepTransportEnabledWhileMonitoringIsDisabled() {
        contextRunner
                .withPropertyValues(
                        "baton.watch.enabled=true",
                        "baton.watch.monitoring-enabled=false",
                        "baton.watch.base-url=https://watch.internal",
                        "baton.watch.bearer-token=watch-token-with-at-least-32-characters",
                        "baton.watch.source-namespace=study-pilot"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    WatchMonitorSource source = context.getBean(WatchMonitorSource.class);
                    assertThat(source.enabled()).isTrue();
                    assertThat(source.monitoringEnabled()).isFalse();
                });
    }

    @Test
    @DisplayName("WATCH 연동을 켤 때 원본 네임스페이스를 명시하지 않으면 시작을 거부한다")
    void rejectMissingSourceNamespaceWhenEnabled() {
        contextRunner
                .withPropertyValues(
                        "baton.watch.enabled=true",
                        "baton.watch.base-url=https://watch.internal",
                        "baton.watch.bearer-token=watch-token-with-at-least-32-characters"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage("WATCH 연동을 켤 때 원본 네임스페이스는 필수입니다");
                });
    }

    @Test
    @DisplayName("WATCH 연동을 켠 상태에서 기본 URL이 비어 있으면 시작을 거부한다")
    void rejectMissingBaseUrlWhenEnabled() {
        contextRunner
                .withPropertyValues(
                        "baton.watch.enabled=true",
                        "baton.watch.source-namespace=study-pilot",
                        "baton.watch.bearer-token=watch-token-with-at-least-32-characters"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage(
                                    "WATCH 기본 URL은 경로, 사용자 정보, 쿼리, 조각이 없는 절대 HTTPS 출처여야 합니다"
                            );
                });
    }

    @Test
    @DisplayName("WATCH 연동을 켠 상태에서 HTTP 기본 URL이면 Bearer 토큰 보호를 위해 시작을 거부한다")
    void rejectHttpBaseUrlWhenEnabled() {
        contextRunner
                .withPropertyValues(
                        "baton.watch.enabled=true",
                        "baton.watch.base-url=http://watch.internal",
                        "baton.watch.source-namespace=study-pilot",
                        "baton.watch.bearer-token=watch-token-with-at-least-32-characters"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage(
                            "WATCH 기본 URL은 경로, 사용자 정보, 쿼리, 조각이 없는 절대 HTTPS 출처여야 합니다"
                    );
                });
    }

    @Test
    @DisplayName("WATCH 기본 URL에 서비스 경로가 포함되면 출처 혼동을 막기 위해 거부한다")
    void rejectBaseUrlWithPath() {
        contextRunner
                .withPropertyValues(
                        "baton.watch.enabled=true",
                        "baton.watch.base-url=https://watch.internal/base",
                        "baton.watch.source-namespace=study-pilot",
                        "baton.watch.bearer-token=watch-token-with-at-least-32-characters"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage(
                            "WATCH 기본 URL은 경로, 사용자 정보, 쿼리, 조각이 없는 절대 HTTPS 출처여야 합니다"
                    );
                });
    }

    @Test
    @DisplayName("WATCH 연동을 켠 상태에서 토큰이 짧으면 시작을 거부한다")
    void rejectShortBearerTokenWhenEnabled() {
        contextRunner
                .withPropertyValues(
                        "baton.watch.enabled=true",
                        "baton.watch.base-url=https://watch.internal",
                        "baton.watch.source-namespace=study-pilot",
                        "baton.watch.bearer-token=too-short"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage(
                                    "WATCH Bearer 토큰은 32~200자의 URL 안전 ASCII여야 합니다"
                            );
                });
    }

    @Test
    @DisplayName("WATCH HTTP 최대 처리 시간이 임대 안전 범위를 넘으면 시작을 거부한다")
    void rejectRequestTimeoutBeyondLeaseBudget() {
        contextRunner
                .withPropertyValues(
                        "baton.watch.enabled=true",
                        "baton.watch.base-url=https://watch.internal",
                        "baton.watch.source-namespace=study-pilot",
                        "baton.watch.bearer-token=watch-token-with-at-least-32-characters",
                        "baton.watch.connect-timeout=PT20S",
                        "baton.watch.read-timeout=PT30S"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage(
                            "WATCH 연결 시간 제한과 읽기 시간 제한의 합은 45초 이하여야 합니다"
                    );
                });
    }

    @Test
    @DisplayName("WATCH 설정 문자열은 URL과 Bearer 토큰을 노출하지 않는다")
    void redactSensitiveConfigurationFromToString() {
        WatchIntegrationProperties properties = new WatchIntegrationProperties(
                true,
                true,
                "https://watch.secret.internal",
                "watch-token-with-at-least-32-characters",
                "primary",
                Duration.ofSeconds(2),
                Duration.ofSeconds(5)
        );

        assertThat(properties.toString())
                .doesNotContain("watch.secret.internal")
                .doesNotContain("watch-token-with-at-least-32-characters")
                .contains("baseUrl=<redacted>")
                .contains("bearerToken=<redacted>");
    }
}
