package com.personal.baton.application.relay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.personal.baton.application.relay.port.in.DispatchRelayOutboxUseCase.DispatchResult;
import com.personal.baton.application.relay.port.out.RelayEventPublisher;
import com.personal.baton.application.relay.port.out.RelayOutboxPort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class RelayOutboxDispatchServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-08T01:02:03Z");

    private final RelayOutboxPort outboxPort = Mockito.mock(RelayOutboxPort.class);
    private final RelayEventPublisher publisher = Mockito.mock(RelayEventPublisher.class);
    private final RelayOutboxPublication publication = publication(1);
    private final RelayOutboxDispatchService service = new RelayOutboxDispatchService(
            outboxPort,
            publisher,
            Clock.fixed(NOW, ZoneOffset.UTC)
    );

    @BeforeEach
    void claimOnePublication() {
        when(outboxPort.claim(NOW, Duration.ofMinutes(1)))
                .thenReturn(Optional.of(publication));
    }

    @DisplayName("대기 사건이 없으면 broker 호출과 결과 transaction을 시작하지 않는다")
    @Test
    void skipsWhenNoPublicationIsDue() {
        when(outboxPort.claim(NOW, Duration.ofMinutes(1))).thenReturn(Optional.empty());

        DispatchResult result = service.dispatchPending();

        assertThat(result).isEqualTo(new DispatchResult(0, 0, 0));
        verify(publisher, never()).publish(any());
        verify(outboxPort, never()).markPublished(any(), any());
        verify(outboxPort, never()).reschedule(any(), any(), any());
    }

    @DisplayName("positive confirm과 return 부재를 확인한 사건만 발행 완료로 확정한다")
    @Test
    void marksOnlyConfirmedPublicationAsPublished() {
        when(publisher.publish(publication)).thenReturn(RelayPublishResult.confirmed());
        when(outboxPort.markPublished(publication, NOW)).thenReturn(true);

        DispatchResult result = service.dispatchPending();

        assertThat(result).isEqualTo(new DispatchResult(1, 1, 0));
        verify(outboxPort).markPublished(publication, NOW);
        verify(outboxPort, never()).reschedule(any(), any(), any());
    }

    @DisplayName("return 또는 nack 같은 미확정 결과는 같은 사건을 backoff 뒤 재시도한다")
    @Test
    void reschedulesUnconfirmedPublication() {
        when(publisher.publish(publication))
                .thenReturn(RelayPublishResult.retryable("MANDATORY_RETURN"));

        DispatchResult result = service.dispatchPending();

        assertThat(result).isEqualTo(new DispatchResult(1, 0, 1));
        verify(outboxPort).reschedule(
                publication,
                NOW.plusSeconds(10),
                "MANDATORY_RETURN"
        );
        verify(outboxPort, never()).markPublished(any(), any());
    }

    @DisplayName("예상하지 못한 publisher 예외도 사건 identity를 바꾸지 않고 재시도한다")
    @Test
    void reschedulesUnexpectedPublisherFailure() {
        when(publisher.publish(publication)).thenThrow(new IllegalStateException("broker down"));

        service.dispatchPending();

        verify(outboxPort).reschedule(
                eq(publication),
                eq(NOW.plusSeconds(10)),
                eq("UNEXPECTED_PUBLISH_FAILURE")
        );
    }

    private RelayOutboxPublication publication(int attemptCount) {
        RelayOutboxEvent event = new RelayOutboxEvent(
                1,
                UUID.randomUUID(),
                "ROLE_HANDOFF_TRANSFERRED",
                1,
                "role:" + UUID.randomUUID(),
                NOW
        );
        return new RelayOutboxPublication(
                1L,
                event,
                attemptCount,
                UUID.randomUUID()
        );
    }
}
