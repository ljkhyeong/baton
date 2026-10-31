package com.personal.baton.bootstrap.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.personal.baton.application.calendar.port.in.CalendarSubscriptionUseCase;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.task.ThreadPoolTaskSchedulerCustomizer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

class SchedulingConfigTest {

    @DisplayName("핵심 작업과 외부 연동 및 운영 지표는 서로 다른 스케줄러에서 실행된다")
    @Test
    void separatesScheduledWorkloadExecutors() {
        Clock customizedClock = Clock.fixed(
                Instant.parse("2026-08-08T00:00:00Z"),
                ZoneOffset.UTC
        );
        ApplicationContextRunner contextRunner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
                .withBean(
                        ThreadPoolTaskSchedulerCustomizer.class,
                        () -> scheduler -> scheduler.setClock(customizedClock)
                )
                .withPropertyValues(
                        "baton.watch.enabled=true",
                        "baton.brief.reconciliation-interval=PT1M",
                        "baton.brief.delivery-enabled=true",
                        "baton.calendar.delivery-enabled=true",
                        "baton.identity.email-verification.delivery=smtp"
                )
                .withUserConfiguration(SchedulingConfig.class);

        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            ThreadPoolTaskScheduler core = context.getBean(
                    "taskScheduler",
                    ThreadPoolTaskScheduler.class
            );
            ThreadPoolTaskScheduler watch = context.getBean(
                    "watchTaskScheduler",
                    ThreadPoolTaskScheduler.class
            );
            ThreadPoolTaskScheduler calendar = context.getBean(
                    "calendarTaskScheduler",
                    ThreadPoolTaskScheduler.class
            );
            ThreadPoolTaskScheduler emailVerification = context.getBean(
                    "emailVerificationTaskScheduler",
                    ThreadPoolTaskScheduler.class
            );
            ThreadPoolTaskScheduler integrationMetrics = context.getBean(
                    "integrationMetricsTaskScheduler",
                    ThreadPoolTaskScheduler.class
            );
            ThreadPoolTaskScheduler brief = context.getBean(
                    "briefTaskScheduler",
                    ThreadPoolTaskScheduler.class
            );
            ThreadPoolTaskScheduler briefDelivery = context.getBean(
                    "briefDeliveryTaskScheduler",
                    ThreadPoolTaskScheduler.class
            );

            assertThat(List.of(
                    core,
                    watch,
                    calendar,
                    brief,
                    briefDelivery,
                    emailVerification,
                    integrationMetrics
            ))
                    .doesNotHaveDuplicates()
                    .allSatisfy(scheduler -> {
                        assertThat(scheduler.getScheduledThreadPoolExecutor()
                                .getRemoveOnCancelPolicy()).isTrue();
                        assertThat(scheduler.getClock()).isSameAs(customizedClock);
                    });
            assertThat(core.getThreadNamePrefix()).isEqualTo("baton-core-scheduler-");
            assertThat(watch.getThreadNamePrefix()).isEqualTo("baton-watch-scheduler-");
            assertThat(calendar.getThreadNamePrefix()).isEqualTo("baton-calendar-scheduler-");
            assertThat(brief.getThreadNamePrefix()).isEqualTo("baton-brief-scheduler-");
            assertThat(briefDelivery.getThreadNamePrefix())
                    .isEqualTo("baton-brief-delivery-scheduler-");
            assertThat(emailVerification.getThreadNamePrefix())
                    .isEqualTo("baton-email-verification-scheduler-");
            assertThat(integrationMetrics.getThreadNamePrefix())
                    .isEqualTo("baton-integration-metrics-scheduler-");
            assertThat(core.getScheduledThreadPoolExecutor().getCorePoolSize()).isOne();
            assertThat(watch.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(2);
            assertThat(calendar.getScheduledThreadPoolExecutor().getCorePoolSize()).isOne();
            assertThat(brief.getScheduledThreadPoolExecutor().getCorePoolSize()).isOne();
            assertThat(briefDelivery.getScheduledThreadPoolExecutor().getCorePoolSize()).isOne();
            assertThat(emailVerification.getScheduledThreadPoolExecutor().getCorePoolSize()).isOne();
            assertThat(integrationMetrics.getScheduledThreadPoolExecutor().getCorePoolSize())
                    .isOne();
        });
    }

    @DisplayName("CAL 구독 폐기는 외부 호출이 핵심 작업을 막지 않게 CAL 전용 스케줄러에서 실행된다")
    @Test
    void runsSubscriptionRevocationOnCalendarScheduler() {
        CalendarSubscriptionUseCase subscriptions = mock(CalendarSubscriptionUseCase.class);
        CountDownLatch executed = new CountDownLatch(1);
        AtomicReference<String> executorThread = new AtomicReference<>();
        doAnswer(invocation -> {
            executorThread.compareAndSet(null, Thread.currentThread().getName());
            executed.countDown();
            return null;
        }).when(subscriptions).revokePending();

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
                .withBean(CalendarSubscriptionUseCase.class, () -> subscriptions)
                .withPropertyValues(
                        "baton.calendar.delivery-enabled=true",
                        "baton.calendar.subscription-revocation-interval=PT0.05S"
                )
                .withUserConfiguration(
                        SchedulingConfig.class,
                        CalendarSubscriptionRevocationScheduler.class
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(executed.await(5, TimeUnit.SECONDS)).isTrue();
                    assertThat(executorThread.get()).startsWith("baton-calendar-scheduler-");
                });
    }

    @DisplayName("CAL 전달을 끄면 구독 폐기 스케줄러와 CAL 전용 실행기를 함께 만들지 않는다")
    @Test
    void omitsCalendarSchedulingWhenDeliveryIsDisabled() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
                .withUserConfiguration(
                        SchedulingConfig.class,
                        CalendarSubscriptionRevocationScheduler.class
                )
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .doesNotHaveBean(CalendarSubscriptionRevocationScheduler.class)
                        .doesNotHaveBean("calendarTaskScheduler"));
    }

    @DisplayName("메일 발송을 비활성화하면 이메일 인증 전용 스케줄러 실행기를 만들지 않는다")
    @Test
    void omitsEmailVerificationSchedulerWhenDeliveryIsDisabled() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
                .withPropertyValues("baton.identity.email-verification.delivery=disabled")
                .withUserConfiguration(SchedulingConfig.class)
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .hasBean("taskScheduler")
                        .hasBean("integrationMetricsTaskScheduler")
                        .doesNotHaveBean("emailVerificationTaskScheduler")
                        .doesNotHaveBean("briefDeliveryTaskScheduler"));
    }
}
