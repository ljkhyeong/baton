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

    @DisplayName("WATCH 외부 호출은 핵심 회차 자동화와 다른 scheduler를 사용한다")
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
                .withPropertyValues("baton.watch.enabled=true")
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

            assertThat(core).isNotSameAs(watch);
            assertThat(core.getThreadNamePrefix()).isEqualTo("baton-core-scheduler-");
            assertThat(watch.getThreadNamePrefix()).isEqualTo("baton-watch-scheduler-");
            assertThat(core.getScheduledThreadPoolExecutor().getCorePoolSize()).isOne();
            assertThat(watch.getScheduledThreadPoolExecutor().getCorePoolSize()).isOne();
            assertThat(core.getScheduledThreadPoolExecutor().getRemoveOnCancelPolicy()).isTrue();
            assertThat(watch.getScheduledThreadPoolExecutor().getRemoveOnCancelPolicy()).isTrue();
            assertThat(core.getClock()).isSameAs(customizedClock);
            assertThat(watch.getClock()).isSameAs(customizedClock);
        });
    }
}
