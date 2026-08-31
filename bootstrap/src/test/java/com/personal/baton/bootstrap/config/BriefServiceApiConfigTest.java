package com.personal.baton.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.personal.baton.adapter.out.external.brief.BriefRestClientFactory;
import com.personal.baton.adapter.out.external.brief.DisabledBriefServiceClient;
import com.personal.baton.adapter.out.external.brief.RestClientBriefServiceClient;
import com.personal.baton.application.brief.port.in.BriefEditionUseCase;
import com.personal.baton.application.brief.port.out.BriefEditionGenerationExecutionPort;
import com.personal.baton.application.brief.port.out.BriefServiceClient;
import com.personal.baton.application.roundauth.port.out.RoundAuthorizationRepository;
import com.personal.baton.application.workspace.port.in.VerifyWorkspaceAccessUseCase;
import com.personal.baton.application.workspace.port.out.WorkspaceRepository;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class BriefServiceApiConfigTest {

    private final BriefRestClientFactory clientFactory = mock(BriefRestClientFactory.class);
    private final RestClientBriefServiceClient client = mock(
            RestClientBriefServiceClient.class
    );
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(VerifyWorkspaceAccessUseCase.class, () -> mock(VerifyWorkspaceAccessUseCase.class))
            .withBean(WorkspaceRepository.class, () -> mock(WorkspaceRepository.class))
            .withBean(RoundAuthorizationRepository.class, () -> mock(RoundAuthorizationRepository.class))
            .withBean(BriefEditionGenerationExecutionPort.class, () -> mock(BriefEditionGenerationExecutionPort.class))
            .withBean(BriefRestClientFactory.class, () -> clientFactory)
            .withBean(Clock.class, Clock::systemUTC)
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

    @DisplayName("BRIEF 서비스 API를 활성화하면 별도 HTTPS origin과 Bearer로 client를 만든다")
    @Test
    void enablesServiceClientOnlyWithHttpsOrigin() {
        when(clientFactory.createEditionServiceClient(
                URI.create("https://brief-service:8443"),
                "brief-service-api-test-token-00000001",
                Duration.ofSeconds(2),
                Duration.ofSeconds(5)
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

    @DisplayName("BRIEF 서비스 API는 사설 주소라도 평문 HTTP origin을 거부한다")
    @Test
    void rejectsPlainHttpOrigin() {
        contextRunner.withPropertyValues(
                "baton.brief.service-api.enabled=true",
                "baton.brief.service-api.base-url=http://brief-service:8080",
                "baton.brief.service-api.bearer-token=brief-service-api-test-token-00000001"
        ).run(context -> assertThat(context).hasFailed());
    }
}
