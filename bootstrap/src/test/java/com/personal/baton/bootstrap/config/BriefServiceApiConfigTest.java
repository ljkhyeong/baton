package com.personal.baton.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.personal.baton.adapter.out.external.brief.BriefRestClientFactory;
import com.personal.baton.adapter.out.external.brief.DisabledBriefServiceClient;
import com.personal.baton.adapter.out.external.brief.RestClientBriefServiceClient;
import com.personal.baton.application.brief.port.in.BriefEditionUseCase;
import com.personal.baton.application.brief.port.out.BriefEditionGenerationExecutionPort;
import com.personal.baton.application.brief.port.out.BriefServiceClient;
import com.personal.baton.application.roundauth.ActiveAccountTeamMembershipVerifier;
import com.personal.baton.application.brief.port.out.BriefContinuitySignalStorePort;
import com.personal.baton.application.workspace.port.in.VerifyWorkspaceAccessUseCase;
import com.personal.baton.application.workspace.port.out.WorkspacePeopleRepository;
import com.personal.baton.application.workspace.port.out.WorkspaceOperationsRepository;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.ssl.SslAutoConfiguration;
import org.springframework.boot.ssl.NoSuchSslBundleException;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class BriefServiceApiConfigTest {

    // 만료가 먼 자체 서명 공개 인증서다. 개인 키는 생성 뒤 폐기했다.
    private static final String TRUSTED_CERTIFICATE_PEM = """
            -----BEGIN CERTIFICATE-----
            MIIBqzCCAVGgAwIBAgIUcoVMr8D0xkN6Ou37VsiOtEKmy0QwCgYIKoZIzj0EAwIw
            HTEbMBkGA1UEAwwSYnJpZWYtc2VydmljZS10ZXN0MCAXDTI2MTAzMDEyMzE0MFoY
            DzIxMjYxMDA2MTIzMTQwWjAdMRswGQYDVQQDDBJicmllZi1zZXJ2aWNlLXRlc3Qw
            WTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAAScN5AI3Q3crWLxAu89CWDAVOZjz0Cq
            TdM6jqZ+oX1DTsVQdXk2D/0LvtQ7DAgLebTSTf603z92X6yZzA+s1H3xo20wazAd
            BgNVHQ4EFgQUtoVDnyjaRzyjJCO6KP8UwNwzoaEwHwYDVR0jBBgwFoAUtoVDnyja
            RzyjJCO6KP8UwNwzoaEwDwYDVR0TAQH/BAUwAwEB/zAYBgNVHREEETAPgg1icmll
            Zi1zZXJ2aWNlMAoGCCqGSM49BAMCA0gAMEUCIDfuZpX1FvhcFbm5mO+5Vjbq19Ko
            ag1hloPdtaq/oX2RAiEAmULChaiG7gOXwUJF3nqBlcCINoUiQHyRM2THV71NNgw=
            -----END CERTIFICATE-----
            """;

    private final BriefRestClientFactory clientFactory = mock(BriefRestClientFactory.class);
    private final RestClientBriefServiceClient client = mock(
            RestClientBriefServiceClient.class
    );
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(VerifyWorkspaceAccessUseCase.class, () -> mock(VerifyWorkspaceAccessUseCase.class))
            .withBean(
                    ActiveAccountTeamMembershipVerifier.class,
                    () -> mock(ActiveAccountTeamMembershipVerifier.class)
            )
            .withBean(WorkspacePeopleRepository.class, () -> mock(WorkspacePeopleRepository.class))
            .withBean(WorkspaceOperationsRepository.class, () -> mock(WorkspaceOperationsRepository.class))
            .withBean(BriefEditionGenerationExecutionPort.class, () -> mock(BriefEditionGenerationExecutionPort.class))
            .withBean(BriefContinuitySignalStorePort.class, () -> mock(BriefContinuitySignalStorePort.class))
            .withBean(BriefRestClientFactory.class, () -> clientFactory)
            .withBean(Clock.class, Clock::systemUTC)
            .withConfiguration(AutoConfigurations.of(SslAutoConfiguration.class))
            .withUserConfiguration(BriefServiceApiConfig.class);

    @DisplayName("BRIEF 서비스 API는 기본 비활성 상태에서도 사용자 API 경계를 구성한다")
    @Test
    void keepsUserApiAvailableWithDisabledClientByDefault() {
        contextRunner.run(context -> assertThat(context)
                .hasNotFailed()
                .hasSingleBean(BriefEditionUseCase.class)
                .getBean(BriefServiceClient.class)
                .isSameAs(DisabledBriefServiceClient.INSTANCE));
    }

    @DisplayName("BRIEF 서비스 API를 활성화하면 별도 HTTPS 출처와 Bearer 토큰으로 클라이언트를 만든다")
    @Test
    void enablesServiceClientOnlyWithHttpsOrigin() {
        when(clientFactory.createEditionServiceClient(
                URI.create("https://brief-service:8443"),
                "brief-service-api-test-token-00000001",
                Duration.ofSeconds(2),
                Duration.ofSeconds(5),
                null
        )).thenReturn(client);

        contextRunner.withPropertyValues(
                "baton.brief.service-api.enabled=true",
                "baton.brief.service-api.base-url=https://brief-service:8443",
                "baton.brief.service-api.bearer-token=brief-service-api-test-token-00000001"
        ).run(context -> assertThat(context)
                .hasNotFailed()
                .getBean(BriefServiceClient.class)
                .isSameAs(client));
    }

    @DisplayName("지정한 PEM SSL 번들을 BRIEF 서비스 클라이언트에 전달한다")
    @Test
    void passesConfiguredPemSslBundleToServiceClient() {
        when(clientFactory.createEditionServiceClient(
                eq(URI.create("https://brief-service:8443")),
                eq("brief-service-api-test-token-00000001"),
                eq(Duration.ofSeconds(2)),
                eq(Duration.ofSeconds(5)),
                any(SslBundle.class)
        )).thenReturn(client);

        contextRunner.withPropertyValues(
                "baton.brief.service-api.enabled=true",
                "baton.brief.service-api.base-url=https://brief-service:8443",
                "baton.brief.service-api.bearer-token=brief-service-api-test-token-00000001",
                "baton.brief.service-api.ssl-bundle=brief-service",
                "spring.ssl.bundle.pem.brief-service.truststore.certificate=" + TRUSTED_CERTIFICATE_PEM
        ).run(context -> {
            assertThat(context).hasNotFailed();
            ArgumentCaptor<SslBundle> sslBundle = ArgumentCaptor.forClass(SslBundle.class);
            verify(clientFactory).createEditionServiceClient(
                    eq(URI.create("https://brief-service:8443")),
                    eq("brief-service-api-test-token-00000001"),
                    eq(Duration.ofSeconds(2)),
                    eq(Duration.ofSeconds(5)),
                    sslBundle.capture()
            );
            assertThat(sslBundle.getValue().getStores().getTrustStore().size()).isEqualTo(1);
        });
    }

    @DisplayName("지정한 SSL 번들이 없으면 인증서 검증을 약화하지 않고 기동을 막는다")
    @Test
    void rejectsMissingSslBundle() {
        contextRunner.withPropertyValues(
                "baton.brief.service-api.enabled=true",
                "baton.brief.service-api.base-url=https://brief-service:8443",
                "baton.brief.service-api.bearer-token=brief-service-api-test-token-00000001",
                "baton.brief.service-api.ssl-bundle=brief-service"
        ).run(context -> assertThat(context)
                .hasFailed()
                .getFailure()
                .hasRootCauseInstanceOf(NoSuchSslBundleException.class));
    }

    @DisplayName("BRIEF 서비스 API는 사설 주소라도 HTTP 출처를 거부한다")
    @Test
    void rejectsPlainHttpOrigin() {
        contextRunner.withPropertyValues(
                "baton.brief.service-api.enabled=true",
                "baton.brief.service-api.base-url=http://brief-service:8080",
                "baton.brief.service-api.bearer-token=brief-service-api-test-token-00000001"
        ).run(context -> assertThat(context).hasFailed());
    }
}
