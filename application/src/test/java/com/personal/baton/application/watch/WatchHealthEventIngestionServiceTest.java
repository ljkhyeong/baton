package com.personal.baton.application.watch;

import com.personal.baton.application.watch.error.WatchHealthEventConflictException;
import com.personal.baton.application.watch.error.WatchHealthEventChangedAtOutOfRangeException;
import com.personal.baton.application.watch.error.WatchHealthEventIdMismatchException;
import com.personal.baton.application.watch.error.WatchHealthEventResourceReferenceException;
import com.personal.baton.application.watch.port.in.AcceptWatchHealthEventUseCase.AcceptWatchHealthEventCommand;
import com.personal.baton.application.watch.port.out.WatchHealthEventInboxPort;
import com.personal.baton.application.watch.port.out.WatchHealthEventInboxPort.WatchHealthEventInboxResult;
import com.personal.baton.application.watch.port.out.WatchHealthEventInboxPort.WatchHealthEventInboxStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WatchHealthEventIngestionServiceTest {

    private static final UUID EVENT_ID =
            UUID.fromString("11111111-2222-4333-8444-555555555555");
    private static final Instant NOW = Instant.parse("2026-08-02T04:05:06.123456Z");
    private static final Instant FIRST_ACCEPTED_AT =
            Instant.parse("2026-08-02T04:05:00.654321Z");

    @DisplayName("WATCH 이벤트를 처음 수신하면 저장소의 최초 접수 시각으로 영수증을 반환한다")
    @Test
    void acceptsEventWithOriginalReceiptTimestamp() {
        WatchHealthEventInboxPort inboxPort = mock(WatchHealthEventInboxPort.class);
        when(inboxPort.accept(any(), any())).thenReturn(new WatchHealthEventInboxResult(
                WatchHealthEventInboxStatus.ACCEPTED,
                FIRST_ACCEPTED_AT
        ));
        WatchHealthEventIngestionService service = service(inboxPort);

        var receipt = service.accept(EVENT_ID, command(
                Instant.parse("2026-08-02T03:04:05.123456789Z")
        ));

        assertThat(receipt.eventId()).isEqualTo(EVENT_ID);
        assertThat(receipt.acceptedAt()).isEqualTo(FIRST_ACCEPTED_AT);
        ArgumentCaptor<WatchHealthChangedEvent> eventCaptor =
                ArgumentCaptor.forClass(WatchHealthChangedEvent.class);
        verify(inboxPort).accept(eventCaptor.capture(), any());
        assertThat(eventCaptor.getValue().changedAt())
                .isEqualTo(Instant.parse("2026-08-02T03:04:05.123456789Z"));
    }

    @DisplayName("Idempotency-Key와 eventId가 다르면 인박스를 호출하지 않고 거절한다")
    @Test
    void rejectsMismatchedIdempotencyKeyBeforePersistence() {
        WatchHealthEventInboxPort inboxPort = mock(WatchHealthEventInboxPort.class);
        WatchHealthEventIngestionService service = service(inboxPort);

        assertThatThrownBy(() -> service.accept(UUID.randomUUID(), command(NOW)))
                .isInstanceOf(WatchHealthEventIdMismatchException.class);
        verify(inboxPort, never()).accept(any(), any());
    }

    @DisplayName("같은 eventId의 다른 envelope는 기존 접수 기록을 바꾸지 않고 충돌로 끝낸다")
    @Test
    void rejectsConflictingEnvelope() {
        WatchHealthEventInboxPort inboxPort = mock(WatchHealthEventInboxPort.class);
        when(inboxPort.accept(any(), any())).thenReturn(new WatchHealthEventInboxResult(
                WatchHealthEventInboxStatus.CONFLICT,
                FIRST_ACCEPTED_AT
        ));
        WatchHealthEventIngestionService service = service(inboxPort);

        assertThatThrownBy(() -> service.accept(EVENT_ID, command(NOW)))
                .isInstanceOf(WatchHealthEventConflictException.class);
    }

    @DisplayName("다른 환경의 resourceReference는 인박스에 저장하지 않는다")
    @Test
    void rejectsForeignResourceReferenceNamespace() {
        WatchHealthEventInboxPort inboxPort = mock(WatchHealthEventInboxPort.class);
        WatchHealthEventIngestionService service = service(inboxPort);
        AcceptWatchHealthEventCommand foreign = new AcceptWatchHealthEventCommand(
                EVENT_ID,
                WatchHealthChangedEvent.RESOURCE_HEALTH_CHANGED,
                "baton-manager:other:role-resource:aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                17L,
                null,
                WatchResourceHealth.DEGRADED,
                WatchResourceHealth.BROKEN,
                NOW
        );

        assertThatThrownBy(() -> service.accept(EVENT_ID, foreign))
                .isInstanceOf(WatchHealthEventResourceReferenceException.class);
        verify(inboxPort, never()).accept(any(), any());
    }

    @DisplayName("변경 전후 health가 같으면 이벤트 envelope 불변식을 거절한다")
    @Test
    void rejectsNonTransitioningHealth() {
        WatchHealthEventInboxPort inboxPort = mock(WatchHealthEventInboxPort.class);
        WatchHealthEventIngestionService service = service(inboxPort);
        AcceptWatchHealthEventCommand invalid = new AcceptWatchHealthEventCommand(
                EVENT_ID,
                WatchHealthChangedEvent.RESOURCE_HEALTH_CHANGED,
                "baton-manager:pilot:role-resource:aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                17L,
                null,
                WatchResourceHealth.HEALTHY,
                WatchResourceHealth.HEALTHY,
                NOW
        );

        assertThatThrownBy(() -> service.accept(EVENT_ID, invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("변경 전후");
        verify(inboxPort, never()).accept(any(), any());
    }

    @DisplayName("MySQL DATETIME 범위를 벗어난 changedAt은 인박스 호출 전에 거절한다")
    @Test
    void rejectsChangedAtOutsidePersistenceRange() {
        WatchHealthEventInboxPort inboxPort = mock(WatchHealthEventInboxPort.class);
        WatchHealthEventIngestionService service = service(inboxPort);

        assertThatThrownBy(() -> service.accept(
                EVENT_ID,
                command(Instant.parse("0999-12-31T23:59:59.999999999Z"))
        )).isInstanceOf(WatchHealthEventChangedAtOutOfRangeException.class);
        assertThatThrownBy(() -> service.accept(
                EVENT_ID,
                command(Instant.parse("+10000-01-01T00:00:00Z"))
        )).isInstanceOf(WatchHealthEventChangedAtOutOfRangeException.class);
        verify(inboxPort, never()).accept(any(), any());
    }

    private WatchHealthEventIngestionService service(WatchHealthEventInboxPort inboxPort) {
        return new WatchHealthEventIngestionService(
                inboxPort,
                new WatchMonitorSource("pilot"),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private AcceptWatchHealthEventCommand command(Instant changedAt) {
        return new AcceptWatchHealthEventCommand(
                EVENT_ID,
                WatchHealthChangedEvent.RESOURCE_HEALTH_CHANGED,
                "baton-manager:pilot:role-resource:aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                17L,
                UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"),
                WatchResourceHealth.DEGRADED,
                WatchResourceHealth.BROKEN,
                changedAt
        );
    }
}
