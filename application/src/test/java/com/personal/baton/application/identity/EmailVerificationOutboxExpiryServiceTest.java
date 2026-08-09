package com.personal.baton.application.identity;

import com.personal.baton.application.identity.port.out.EmailVerificationOutboxPort;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("usecase")
class EmailVerificationOutboxExpiryServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-09T04:00:00Z");

    @DisplayName("만료 정리는 발송 여부와 무관하게 서버 Clock 시각으로 outbox payload를 폐기한다")
    @Test
    void expiresUndeliverablePayloadsAtServerTime() {
        EmailVerificationOutboxPort outboxPort = mock(EmailVerificationOutboxPort.class);
        when(outboxPort.expireUndeliverable(NOW)).thenReturn(2);
        var service = new EmailVerificationOutboxExpiryService(
                outboxPort,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );

        assertThat(service.expireUndeliverable()).isEqualTo(2);

        verify(outboxPort).expireUndeliverable(NOW);
    }
}
