package com.personal.baton.application.watch;

import com.personal.baton.application.watch.port.out.WatchMonitorClient;
import com.personal.baton.application.watch.port.out.WatchMonitorOutboxPort;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WatchMonitorOutboxDispatchServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-01T04:00:00Z");

    @DisplayName("WATCH 성공과 stale revision은 같은 outbox 전달 완료로 수렴한다")
    @Test
    void completesDeliveredAndStaleMessages() {
        WatchMonitorOutboxPort outboxPort = mock(WatchMonitorOutboxPort.class);
        WatchMonitorClient client = mock(WatchMonitorClient.class);
        WatchMonitorDelivery delivered = delivery(10L, WatchMonitoringState.ACTIVE);
        WatchMonitorDelivery stale = delivery(11L, WatchMonitoringState.INACTIVE);
        when(outboxPort.claimPending(anyInt(), eq(NOW), any()))
                .thenReturn(List.of(delivered, stale));
        when(client.synchronize(delivered))
                .thenReturn(WatchMonitorClient.SynchronizationResult.delivered());
        when(client.synchronize(stale))
                .thenReturn(WatchMonitorClient.SynchronizationResult.stale());
        when(outboxPort.markDelivered(anyLong(), any(), eq(NOW), nullable(String.class)))
                .thenReturn(true);

        var result = service(outboxPort, client).dispatchPending();

        assertThat(result.claimedCount()).isEqualTo(2);
        assertThat(result.deliveredCount()).isEqualTo(2);
        assertThat(result.failedCount()).isZero();
        verify(outboxPort).markDelivered(10L, delivered.leaseToken(), NOW, null);
        verify(outboxPort).markDelivered(
                11L,
                stale.leaseToken(),
                NOW,
                "STALE_SOURCE_REVISION"
        );
    }

    @DisplayName("일시적인 WATCH 실패는 지수 backoff 뒤 다시 시도한다")
    @Test
    void retriesTransientFailureWithBackoff() {
        WatchMonitorOutboxPort outboxPort = mock(WatchMonitorOutboxPort.class);
        WatchMonitorClient client = mock(WatchMonitorClient.class);
        WatchMonitorDelivery delivery = delivery(20L, WatchMonitoringState.ACTIVE);
        when(outboxPort.claimPending(anyInt(), eq(NOW), any()))
                .thenReturn(List.of(delivery));
        when(client.synchronize(delivery))
                .thenReturn(WatchMonitorClient.SynchronizationResult.retryable("WATCH_503"));

        var result = service(outboxPort, client).dispatchPending();

        assertThat(result.failedCount()).isEqualTo(1);
        verify(outboxPort).markRetry(
                20L,
                delivery.leaseToken(),
                NOW.plusSeconds(10),
                "WATCH_503"
        );
    }

    @DisplayName("WATCH가 ACTIVE URL을 거절하면 실패 기록과 INACTIVE 보상 snapshot을 원자적으로 남긴다")
    @Test
    void compensatesInvalidTargetWithInactiveSnapshot() {
        WatchMonitorOutboxPort outboxPort = mock(WatchMonitorOutboxPort.class);
        WatchMonitorClient client = mock(WatchMonitorClient.class);
        WatchMonitorDelivery delivery = delivery(30L, WatchMonitoringState.ACTIVE);
        when(outboxPort.claimPending(anyInt(), eq(NOW), any()))
                .thenReturn(List.of(delivery));
        when(client.synchronize(delivery))
                .thenReturn(WatchMonitorClient.SynchronizationResult.invalidTarget());
        when(outboxPort.markInvalidTargetAndAppendInactive(
                eq(30L),
                eq(delivery.leaseToken()),
                any(),
                eq(NOW)
        )).thenReturn(true);

        var result = service(outboxPort, client).dispatchPending();

        assertThat(result.deliveredCount()).isZero();
        assertThat(result.failedCount()).isEqualTo(1);
        verify(outboxPort).markInvalidTargetAndAppendInactive(
                eq(30L),
                eq(delivery.leaseToken()),
                any(),
                eq(NOW)
        );
    }

    private WatchMonitorOutboxDispatchService service(
            WatchMonitorOutboxPort outboxPort,
            WatchMonitorClient client
    ) {
        return new WatchMonitorOutboxDispatchService(
                outboxPort,
                client,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private WatchMonitorDelivery delivery(long revision, WatchMonitoringState state) {
        return new WatchMonitorDelivery(
                revision,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "baton-manager:study-pilot:role-resource:" + UUID.randomUUID(),
                state,
                state == WatchMonitoringState.ACTIVE ? "https://docs.example.com/study" : null,
                1,
                UUID.randomUUID()
        );
    }
}
