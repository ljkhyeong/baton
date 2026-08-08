package com.personal.baton.bootstrap.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
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
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanPostProcessor;
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

    @Test
    @DisplayName("WATCH context는 namespace 검증과 시작 복구를 마친 뒤 scheduler를 초기화한다")
    void initializeSchedulerAfterFailClosedStartupRecovery() {
        List<String> initializationOrder = new ArrayList<>();
        RecoverWatchMonitorOutboxUseCase recover = mock(RecoverWatchMonitorOutboxUseCase.class);
        doAnswer(invocation -> {
            initializationOrder.add("namespace-validation");
            return null;
        }).when(recover).validateSourceNamespace();
        when(recover.requeueOperationalFailures()).thenAnswer(invocation -> {
            initializationOrder.add("outbox-requeue");
            return 0;
        });

        orderedSchedulerContextRunner(recover, initializationOrder)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(WatchMonitorScheduler.class);
                    assertThat(initializationOrder).containsExactly(
                            "namespace-validation",
                            "outbox-requeue",
                            "scheduler-initialization"
                    );
                });
    }

    @Test
    @DisplayName("WATCH namespace 검증이 실패하면 scheduler를 초기화하지 않고 context 시작을 거부한다")
    void rejectContextBeforeSchedulerWhenNamespaceValidationFails() {
        List<String> initializationOrder = new ArrayList<>();
        RecoverWatchMonitorOutboxUseCase recover = mock(RecoverWatchMonitorOutboxUseCase.class);
        doAnswer(invocation -> {
            initializationOrder.add("namespace-validation");
            throw new IllegalStateException("namespace mismatch");
        }).when(recover).validateSourceNamespace();

        orderedSchedulerContextRunner(recover, initializationOrder)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage("namespace mismatch");
                    assertThat(initializationOrder).containsExactly("namespace-validation");
                });
    }

    private ApplicationContextRunner orderedSchedulerContextRunner(
            RecoverWatchMonitorOutboxUseCase recover,
            List<String> initializationOrder
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
                        () -> new WatchEventReceiverProperties(false, null)
                )
                .withBean(
                        DispatchWatchMonitorOutboxUseCase.class,
                        () -> mock(DispatchWatchMonitorOutboxUseCase.class)
                )
                .withBean(
                        ReconcileWatchMonitorsUseCase.class,
                        () -> mock(ReconcileWatchMonitorsUseCase.class)
                )
                .withBean("watchSchedulerInitializationRecorder", BeanPostProcessor.class, () ->
                        new BeanPostProcessor() {
                            @Override
                            public Object postProcessAfterInitialization(Object bean, String beanName) {
                                if (bean instanceof WatchMonitorScheduler) {
                                    initializationOrder.add("scheduler-initialization");
                                }
                                return bean;
                            }
                        })
                .withUserConfiguration(OrderedSchedulerTestConfig.class);
    }

    private static WatchIntegrationProperties enabledWatchProperties() {
        return new WatchIntegrationProperties(
                true,
                true,
                "https://watch.internal",
                "outbound-token-with-at-least-32-characters",
                "study-pilot",
                null,
                null
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

        @Bean
        WatchMonitorStartupRecovery watchMonitorStartupRecovery() {
            return mock(WatchMonitorStartupRecovery.class);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import({WatchMonitorStartupRecovery.class, WatchMonitorScheduler.class})
    static class OrderedSchedulerTestConfig {
    }
}
