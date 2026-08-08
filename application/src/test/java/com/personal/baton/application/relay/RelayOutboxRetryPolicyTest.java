package com.personal.baton.application.relay;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RelayOutboxRetryPolicyTest {

    private final RelayOutboxRetryPolicy retryPolicy = new RelayOutboxRetryPolicy();

    @DisplayName("RELAY outbox 재시도는 첫 10초부터 시도마다 두 배로 지연한다")
    @Test
    void doublesDelayFromTenSeconds() {
        assertThat(retryPolicy.delayAfterAttempt(1)).isEqualTo(Duration.ofSeconds(10));
        assertThat(retryPolicy.delayAfterAttempt(2)).isEqualTo(Duration.ofSeconds(20));
        assertThat(retryPolicy.delayAfterAttempt(3)).isEqualTo(Duration.ofSeconds(40));
    }

    @DisplayName("RELAY outbox 재시도 지연은 큰 시도 횟수에서도 한 시간을 넘지 않는다")
    @Test
    void capsDelayAtOneHourWithoutOverflow() {
        assertThat(retryPolicy.delayAfterAttempt(10)).isEqualTo(Duration.ofHours(1));
        assertThat(retryPolicy.delayAfterAttempt(Integer.MAX_VALUE))
                .isEqualTo(Duration.ofHours(1));
    }
}
