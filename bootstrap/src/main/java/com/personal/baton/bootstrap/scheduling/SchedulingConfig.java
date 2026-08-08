package com.personal.baton.bootstrap.scheduling;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
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

    @Bean("watchTaskScheduler")
    @ConditionalOnBooleanProperty(prefix = "baton.watch", name = "enabled")
    ThreadPoolTaskScheduler watchTaskScheduler(ThreadPoolTaskSchedulerBuilder builder) {
        return scheduler(builder, "baton-watch-scheduler-", 2);
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
