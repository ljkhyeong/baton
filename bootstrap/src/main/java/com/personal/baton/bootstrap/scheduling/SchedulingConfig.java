package com.personal.baton.bootstrap.scheduling;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
class SchedulingConfig {

    @Bean
    ThreadPoolTaskScheduler taskScheduler(ThreadPoolTaskSchedulerBuilder builder) {
        return scheduler(builder, "baton-core-scheduler-", 1);
    }

    @Bean("integrationMetricsTaskScheduler")
    ThreadPoolTaskScheduler integrationMetricsTaskScheduler(
            ThreadPoolTaskSchedulerBuilder builder
    ) {
        return scheduler(builder, "baton-integration-metrics-scheduler-", 1);
    }

    @Bean("watchTaskScheduler")
    @ConditionalOnBooleanProperty(prefix = "baton.watch", name = "enabled")
    ThreadPoolTaskScheduler watchTaskScheduler(ThreadPoolTaskSchedulerBuilder builder) {
        return scheduler(builder, "baton-watch-scheduler-", 2);
    }

    @Bean("briefTaskScheduler")
    @ConditionalOnProperty(prefix = "baton.brief", name = "reconciliation-interval")
    ThreadPoolTaskScheduler briefTaskScheduler(ThreadPoolTaskSchedulerBuilder builder) {
        return scheduler(builder, "baton-brief-scheduler-", 1);
    }

    @Bean("briefDeliveryTaskScheduler")
    @ConditionalOnBooleanProperty(prefix = "baton.brief", name = "delivery-enabled")
    ThreadPoolTaskScheduler briefDeliveryTaskScheduler(
            ThreadPoolTaskSchedulerBuilder builder
    ) {
        return scheduler(builder, "baton-brief-delivery-scheduler-", 1);
    }

    @Bean("calendarTaskScheduler")
    @ConditionalOnBooleanProperty(prefix = "baton.calendar", name = "delivery-enabled")
    ThreadPoolTaskScheduler calendarTaskScheduler(ThreadPoolTaskSchedulerBuilder builder) {
        return scheduler(builder, "baton-calendar-scheduler-", 1);
    }

    @Bean("emailVerificationTaskScheduler")
    @ConditionalOnProperty(
            prefix = "baton.identity.email-verification",
            name = "delivery",
            havingValue = "smtp"
    )
    ThreadPoolTaskScheduler emailVerificationTaskScheduler(
            ThreadPoolTaskSchedulerBuilder builder
    ) {
        return scheduler(builder, "baton-email-verification-scheduler-", 1);
    }

    private ThreadPoolTaskScheduler scheduler(
            ThreadPoolTaskSchedulerBuilder builder,
            String threadNamePrefix,
            int poolSize
    ) {
        return builder
                .poolSize(poolSize)
                .threadNamePrefix(threadNamePrefix)
                .additionalCustomizers(scheduler -> scheduler.setRemoveOnCancelPolicy(true))
                .build();
    }
}
