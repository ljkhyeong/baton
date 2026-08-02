package com.personal.baton.bootstrap.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.personal.baton.application.watch.port.in.DispatchWatchMonitorOutboxUseCase;
import com.personal.baton.application.watch.port.in.DispatchWatchMonitorOutboxUseCase.DispatchResult;
import com.personal.baton.application.watch.port.in.ReconcileWatchMonitorsUseCase;
import com.personal.baton.application.watch.port.in.ReconcileWatchMonitorsUseCase.ReconciliationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

class WatchMonitorSchedulerTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(SchedulerTestConfig.class);

    @Test
    @DisplayName("WATCH scheduler는 연동이 비활성일 때 조립되지 않는다")
    void doNotCreateSchedulerWhenDisabled() {
        contextRunner
                .withPropertyValues("baton.watch.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(WatchMonitorScheduler.class));
    }

    @Test
    @DisplayName("WATCH scheduler는 연동이 활성일 때 전달과 정합성 port를 호출한다")
    void invokeUseCasesWhenEnabled() {
        contextRunner
                .withPropertyValues("baton.watch.enabled=true")
                .run(context -> {
                    WatchMonitorScheduler scheduler = context.getBean(WatchMonitorScheduler.class);
                    DispatchWatchMonitorOutboxUseCase dispatch =
                            context.getBean(DispatchWatchMonitorOutboxUseCase.class);
                    ReconcileWatchMonitorsUseCase reconcile =
                            context.getBean(ReconcileWatchMonitorsUseCase.class);
                    when(dispatch.dispatchPending()).thenReturn(new DispatchResult(1, 1, 0));
                    when(reconcile.reconcile()).thenReturn(new ReconciliationResult(1, 1));

                    scheduler.dispatchPending();
                    scheduler.reconcile();

                    verify(dispatch).dispatchPending();
                    verify(reconcile).reconcile();
                });
    }

    @Configuration(proxyBeanMethods = false)
    @Import(WatchMonitorScheduler.class)
    static class SchedulerTestConfig {

        @Bean
        DispatchWatchMonitorOutboxUseCase dispatchWatchMonitorOutboxUseCase() {
            return mock(DispatchWatchMonitorOutboxUseCase.class);
        }

        @Bean
        ReconcileWatchMonitorsUseCase reconcileWatchMonitorsUseCase() {
            return mock(ReconcileWatchMonitorsUseCase.class);
        }
    }
}
