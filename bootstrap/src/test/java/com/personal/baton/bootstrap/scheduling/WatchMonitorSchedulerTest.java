package com.personal.baton.bootstrap.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.personal.baton.application.watch.port.in.DispatchWatchMonitorOutboxUseCase;
import com.personal.baton.application.watch.port.in.DispatchWatchMonitorOutboxUseCase.DispatchResult;
import com.personal.baton.application.watch.port.in.RecoverWatchMonitorOutboxUseCase;
import com.personal.baton.application.watch.port.in.ReconcileWatchMonitorsUseCase;
import com.personal.baton.application.watch.port.in.ReconcileWatchMonitorsUseCase.ReconciliationResult;
import com.personal.baton.bootstrap.config.WatchEventReceiverProperties;
import com.personal.baton.bootstrap.config.WatchIntegrationProperties;
import java.time.Duration;
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
    @DisplayName("WATCH 연동이 비활성화되면 스케줄러를 등록하지 않는다")
    void doNotCreateSchedulerWhenDisabled() {
        contextRunner
                .withPropertyValues("baton.watch.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(WatchMonitorScheduler.class));
    }

    @Test
    @DisplayName("WATCH 스케줄러는 연동이 활성일 때 전달과 상태 조정 포트를 호출한다")
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

    @Test
    @DisplayName("WATCH 애플리케이션은 스케줄러와 시작 복구 작업을 함께 구성한다")
    void initializeSchedulerAndStartupRecovery() {
        RecoverWatchMonitorOutboxUseCase recover = mock(RecoverWatchMonitorOutboxUseCase.class);

        schedulerContextRunner(recover)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(WatchMonitorScheduler.class);
                    verify(recover).validateSourceNamespace();
                    verify(recover).requeueOperationalFailures();
                });
    }

    @Test
    @DisplayName("WATCH 네임스페이스 검증이 실패하면 애플리케이션 시작을 거부한다")
    void rejectContextWhenNamespaceValidationFails() {
        RecoverWatchMonitorOutboxUseCase recover = mock(RecoverWatchMonitorOutboxUseCase.class);
        doThrow(new IllegalStateException("namespace mismatch"))
                .when(recover)
                .validateSourceNamespace();

        schedulerContextRunner(recover)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage("namespace mismatch");
                });
    }

    private ApplicationContextRunner schedulerContextRunner(
            RecoverWatchMonitorOutboxUseCase recover
    ) {
        return new ApplicationContextRunner()
                .withPropertyValues("baton.watch.enabled=true")
                .withBean(RecoverWatchMonitorOutboxUseCase.class, () -> recover)
                .withBean(
                        WatchIntegrationProperties.class,
                        WatchMonitorSchedulerTest::enabledWatchProperties
                )
                .withBean(
                        WatchEventReceiverProperties.class,
                        () -> new WatchEventReceiverProperties(false, "")
                )
                .withBean(
                        DispatchWatchMonitorOutboxUseCase.class,
                        () -> mock(DispatchWatchMonitorOutboxUseCase.class)
                )
                .withBean(
                        ReconcileWatchMonitorsUseCase.class,
                        () -> mock(ReconcileWatchMonitorsUseCase.class)
                )
                .withUserConfiguration(SchedulerRecoveryTestConfig.class);
    }

    private static WatchIntegrationProperties enabledWatchProperties() {
        return new WatchIntegrationProperties(
                true,
                true,
                "https://watch.internal",
                "outbound-token-with-at-least-32-characters",
                "study-pilot",
                Duration.ofSeconds(2),
                Duration.ofSeconds(5)
        );
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

    @Configuration(proxyBeanMethods = false)
    @Import({WatchMonitorStartupRecovery.class, WatchMonitorScheduler.class})
    static class SchedulerRecoveryTestConfig {
    }
}
