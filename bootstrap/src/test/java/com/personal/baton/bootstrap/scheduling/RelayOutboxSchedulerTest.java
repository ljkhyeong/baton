package com.personal.baton.bootstrap.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.personal.baton.application.relay.port.in.DispatchRelayOutboxUseCase;
import com.personal.baton.application.relay.port.in.DispatchRelayOutboxUseCase.DispatchResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

class RelayOutboxSchedulerTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(SchedulerTestConfig.class);

    @DisplayName("RELAY publisher가 비활성일 때 전용 scheduler를 조립하지 않는다")
    @Test
    void doesNotCreateSchedulerWhenDisabled() {
        contextRunner
                .withPropertyValues("baton.relay.publisher.enabled=false")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(RelayOutboxScheduler.class));
    }

    @DisplayName("RELAY publisher가 활성일 때 scheduler는 outbox dispatch use case만 호출한다")
    @Test
    void invokesDispatchWhenEnabled() {
        contextRunner
                .withPropertyValues("baton.relay.publisher.enabled=true")
                .run(context -> {
                    RelayOutboxScheduler scheduler = context.getBean(RelayOutboxScheduler.class);
                    DispatchRelayOutboxUseCase dispatch =
                            context.getBean(DispatchRelayOutboxUseCase.class);
                    when(dispatch.dispatchPending()).thenReturn(new DispatchResult(1, 1, 0));

                    scheduler.dispatchPending();

                    verify(dispatch).dispatchPending();
                });
    }

    @Configuration(proxyBeanMethods = false)
    @Import(RelayOutboxScheduler.class)
    static class SchedulerTestConfig {

        @Bean
        DispatchRelayOutboxUseCase dispatchRelayOutboxUseCase() {
            return mock(DispatchRelayOutboxUseCase.class);
        }
    }
}
