package com.personal.baton.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.personal.baton.adapter.out.external.brief.RestClientBriefContinuityClient;
import com.personal.baton.application.brief.port.in.DispatchBriefContinuityOutboxUseCase;
import com.personal.baton.application.brief.port.out.BriefContinuityClient;
import com.personal.baton.application.brief.port.out.BriefContinuityOutboxPort;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class BriefIntegrationConfigTest {

    private final RestClientBriefContinuityClient.Factory clientFactory = mock(
            RestClientBriefContinuityClient.Factory.class
    );
    private final RestClientBriefContinuityClient client = mock(
            RestClientBriefContinuityClient.class
    );
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(BriefContinuityOutboxPort.class, () -> mock(BriefContinuityOutboxPort.class))
            .withBean(Clock.class, Clock::systemUTC)
            .withBean(RestClientBriefContinuityClient.Factory.class, () -> clientFactory)
            .withUserConfiguration(BriefIntegrationConfig.class);

    @DisplayName("BRIEF 전달은 기본 비활성이고 명시적으로 활성화할 때만 client와 use case를 만든다")
    @Test
    void enablesDeliveryOnlyWhenConfigured() {
        when(clientFactory.create(
                URI.create("http://127.0.0.1:8080"),
                Duration.ofSeconds(2),
                Duration.ofSeconds(5)
        )).thenReturn(client);

        contextRunner.run(context -> assertThat(context)
                .hasNotFailed()
                .doesNotHaveBean(BriefContinuityClient.class)
                .doesNotHaveBean(DispatchBriefContinuityOutboxUseCase.class));

        contextRunner.withPropertyValues(
                "baton.brief.delivery-enabled=true",
                "baton.brief.base-url=http://127.0.0.1:8080"
        ).run(context -> assertThat(context)
                .hasNotFailed()
                .hasSingleBean(BriefContinuityClient.class)
                .hasSingleBean(DispatchBriefContinuityOutboxUseCase.class));
    }

    @DisplayName("BRIEF 전달은 loopback이 아닌 평문 HTTP origin을 거부한다")
    @Test
    void rejectsExternalPlainHttpOrigin() {
        contextRunner.withPropertyValues(
                "baton.brief.delivery-enabled=true",
                "baton.brief.base-url=http://brief.internal"
        ).run(context -> assertThat(context).hasFailed());
    }
}
