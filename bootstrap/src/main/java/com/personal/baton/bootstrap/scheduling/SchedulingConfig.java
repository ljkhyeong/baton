package com.personal.baton.bootstrap.scheduling;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
class SchedulingConfig {

    @Bean
    ThreadPoolTaskScheduler taskScheduler() {
        return scheduler("baton-core-scheduler-");
    }

    @Bean("watchTaskScheduler")
    @ConditionalOnBooleanProperty(prefix = "baton.watch", name = "enabled")
    ThreadPoolTaskScheduler watchTaskScheduler() {
        return scheduler("baton-watch-scheduler-");
    }

    private ThreadPoolTaskScheduler scheduler(String threadNamePrefix) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix(threadNamePrefix);
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }
}
