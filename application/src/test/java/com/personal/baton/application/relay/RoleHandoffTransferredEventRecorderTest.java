package com.personal.baton.application.relay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.personal.baton.application.relay.port.out.RelayOutboxPort;
import com.personal.baton.domain.workspace.RoleHandoff;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class RoleHandoffTransferredEventRecorderTest {

    private static final UUID ROLE_ID = UUID.randomUUID();
    private static final UUID FROM_MEMBER_ID = UUID.randomUUID();
    private static final UUID TO_MEMBER_ID = UUID.randomUUID();
    private static final Instant TRANSFERRED_AT = Instant.parse("2026-08-08T01:02:03Z");

    private final RelayOutboxPort outboxPort = Mockito.mock(RelayOutboxPort.class);
    private final RoleHandoffTransferredEventRecorder recorder =
            new RoleHandoffTransferredEventRecorder(outboxPort);

    @DisplayName("최초 역할 바통 전달은 역할 구독 기준의 RELAY 사건을 기록한다")
    @Test
    void recordsTransferredHandoff() {
        RoleHandoff handoff = preparedHandoff();
        handoff.transfer(FROM_MEMBER_ID, TRANSFERRED_AT, 1, 0, 1, false);

        recorder.record(handoff);

        ArgumentCaptor<RelayOutboxEvent> eventCaptor =
                ArgumentCaptor.forClass(RelayOutboxEvent.class);
        verify(outboxPort).append(eventCaptor.capture());
        RelayOutboxEvent event = eventCaptor.getValue();
        assertThat(event.contractVersion()).isEqualTo(1);
        assertThat(event.eventType()).isEqualTo("ROLE_HANDOFF_TRANSFERRED");
        assertThat(event.eventVersion()).isEqualTo(1);
        assertThat(event.subjectReference()).isEqualTo("role:" + ROLE_ID);
        assertThat(event.occurredAt()).isEqualTo(TRANSFERRED_AT);
    }

    @DisplayName("전달 전 역할 바통은 RELAY 사건으로 기록할 수 없다")
    @Test
    void rejectsHandoffBeforeTransfer() {
        assertThatThrownBy(() -> recorder.record(preparedHandoff()))
                .isInstanceOf(IllegalArgumentException.class);

        verify(outboxPort, never()).append(any());
    }

    private RoleHandoff preparedHandoff() {
        return RoleHandoff.prepare(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                ROLE_ID,
                FROM_MEMBER_ID,
                TO_MEMBER_ID,
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 7, 31),
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 31),
                Instant.parse("2026-08-01T01:02:03Z")
        );
    }
}
