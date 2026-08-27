package com.personal.baton.bootstrap.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.task.ThreadPoolTaskSchedulerCustomizer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

class SchedulingConfigTest {

    @DisplayName("CAL과 WATCH 전달은 핵심 회차 및 서로의 외부 호출과 격리된다")
    @Test
    void separatesCoreAndWatchSchedulers() {
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

            assertThat(core).isNotSameAs(watch);
            assertThat(calendar).isNotSameAs(core).isNotSameAs(watch);
            assertThat(emailVerification)
                    .isNotSameAs(core)
                    .isNotSameAs(watch)
                    .isNotSameAs(calendar);
            assertThat(core.getThreadNamePrefix()).isEqualTo("baton-core-scheduler-");
            assertThat(watch.getThreadNamePrefix()).isEqualTo("baton-watch-scheduler-");
            assertThat(calendar.getThreadNamePrefix()).isEqualTo("baton-calendar-scheduler-");
            assertThat(emailVerification.getThreadNamePrefix())
                    .isEqualTo("baton-email-verification-scheduler-");
            assertThat(core.getScheduledThreadPoolExecutor().getCorePoolSize()).isOne();
            assertThat(watch.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(2);
            assertThat(calendar.getScheduledThreadPoolExecutor().getCorePoolSize()).isOne();
            assertThat(emailVerification.getScheduledThreadPoolExecutor().getCorePoolSize()).isOne();
            assertThat(core.getScheduledThreadPoolExecutor().getRemoveOnCancelPolicy()).isTrue();
            assertThat(watch.getScheduledThreadPoolExecutor().getRemoveOnCancelPolicy()).isTrue();
            assertThat(calendar.getScheduledThreadPoolExecutor().getRemoveOnCancelPolicy()).isTrue();
            assertThat(emailVerification.getScheduledThreadPoolExecutor().getRemoveOnCancelPolicy())
                    .isTrue();
            assertThat(core.getClock()).isSameAs(customizedClock);
            assertThat(watch.getClock()).isSameAs(customizedClock);
            assertThat(calendar.getClock()).isSameAs(customizedClock);
            assertThat(emailVerification.getClock()).isSameAs(customizedClock);
        });
    }

    @DisplayName("메일 발송을 비활성화하면 이메일 인증 전용 scheduler 실행기를 만들지 않는다")
    @Test
    void omitsEmailVerificationSchedulerWhenDeliveryIsDisabled() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
                .withPropertyValues("baton.identity.email-verification.delivery=disabled")
                .withUserConfiguration(SchedulingConfig.class)
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .hasBean("taskScheduler")
                        .doesNotHaveBean("emailVerificationTaskScheduler"));
    }
}
