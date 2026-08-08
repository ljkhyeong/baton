package com.personal.baton.application.relay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RelayOutboxEventTest {

    @DisplayName("RELAY outbox 사건은 wire 계약을 검증하고 발생 시각을 마이크로초로 고정한다")
    @Test
    void validatesAndCanonicalizesEnvelope() {
        UUID eventId = UUID.randomUUID();

        RelayOutboxEvent event = new RelayOutboxEvent(
                1,
                eventId,
                "ROLE_HANDOFF_TRANSFERRED",
                1,
                "role:" + UUID.randomUUID(),
                Instant.parse("2026-08-08T01:02:03.123456789Z")
        );

        assertThat(event.contractVersion()).isEqualTo(1);
        assertThat(event.eventId()).isEqualTo(eventId);
        assertThat(event.occurredAt())
                .isEqualTo(Instant.parse("2026-08-08T01:02:03.123456Z"));
    }

    @DisplayName("지원하지 않는 계약 버전과 잘못된 사건 필드는 outbox에 들어갈 수 없다")
    @Test
    void rejectsInvalidEnvelope() {
        UUID eventId = UUID.randomUUID();
        Instant occurredAt = Instant.parse("2026-08-08T01:02:03Z");

        assertThatThrownBy(() -> new RelayOutboxEvent(
                2, eventId, "ROLE_HANDOFF_TRANSFERRED", 1, "role:42", occurredAt
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RelayOutboxEvent(
                1, eventId, "role_handoff_transferred", 1, "role:42", occurredAt
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RelayOutboxEvent(
                1, eventId, "ROLE_HANDOFF_TRANSFERRED", 0, "role:42", occurredAt
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RelayOutboxEvent(
                1, eventId, "ROLE_HANDOFF_TRANSFERRED", 1, "contact@example.test", occurredAt
        )).isInstanceOf(IllegalArgumentException.class);
    }
}
