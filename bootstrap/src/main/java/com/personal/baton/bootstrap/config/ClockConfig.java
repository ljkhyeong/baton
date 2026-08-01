package com.personal.baton.bootstrap.config;

import java.time.Clock;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    @Bean
    Clock clock() {
        return databaseCompatibleClock(Clock.systemUTC());
    }

    static Clock databaseCompatibleClock(Clock sourceClock) {
        return Clock.tick(sourceClock, Duration.ofNanos(1_000));
    }
}
