package com.personal.baton.adapter.out.external.watch;

import static org.assertj.core.api.Assertions.assertThat;

import com.personal.baton.application.watch.WatchMonitorDelivery;
import com.personal.baton.application.watch.WatchMonitoringState;
import com.personal.baton.application.watch.port.out.WatchMonitorClient.Outcome;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DisabledWatchMonitorClientTest {

    @Test
    @DisplayName("비활성 WATCH client는 외부 호출 없이 재시도 가능한 결과를 반환한다")
    void returnDisabledResult() {
        DisabledWatchMonitorClient client = new DisabledWatchMonitorClient();
        WatchMonitorDelivery delivery = new WatchMonitorDelivery(
                1,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "resource-1",
                WatchMonitoringState.INACTIVE,
                null,
                1,
                UUID.randomUUID()
        );

        var result = client.synchronize(delivery);

        assertThat(result.outcome()).isEqualTo(Outcome.RETRYABLE_FAILURE);
        assertThat(result.code()).isEqualTo("WATCH_DISABLED");
    }
}
