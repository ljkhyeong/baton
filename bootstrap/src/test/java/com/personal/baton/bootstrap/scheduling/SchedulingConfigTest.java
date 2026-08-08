package com.personal.baton.bootstrap.scheduling;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import static org.assertj.core.api.Assertions.assertThat;

class SchedulingConfigTest {

    @DisplayName("WATCH와 RELAY 외부 호출은 핵심 회차 자동화와 서로 다른 scheduler를 사용한다")
    @Test
    void separatesCoreAndWatchSchedulers() {
        SchedulingConfig config = new SchedulingConfig();

        ThreadPoolTaskScheduler core = config.taskScheduler();
        ThreadPoolTaskScheduler watch = config.watchTaskScheduler();
        ThreadPoolTaskScheduler relay = config.relayPublisherTaskScheduler();

        assertThat(core).isNotSameAs(watch);
        assertThat(core).isNotSameAs(relay);
        assertThat(watch).isNotSameAs(relay);
        assertThat(core.getThreadNamePrefix()).isEqualTo("baton-core-scheduler-");
        assertThat(watch.getThreadNamePrefix()).isEqualTo("baton-watch-scheduler-");
        assertThat(relay.getThreadNamePrefix()).isEqualTo("baton-relay-publisher-");
        assertThat(core.getPoolSize()).isOne();
        assertThat(watch.getPoolSize()).isOne();
        assertThat(relay.getPoolSize()).isOne();
    }
}
