package com.personal.baton.application.identity;

import com.personal.baton.application.identity.error.EmailVerificationDeliveryUnavailableException;
import com.personal.baton.application.identity.error.EmailVerificationPayloadProtectionException;
import com.personal.baton.application.identity.port.out.EmailVerificationDeliveryPort;
import com.personal.baton.application.identity.port.out.EmailVerificationDeliveryPort.EmailVerificationDelivery;
import com.personal.baton.application.identity.port.out.EmailVerificationOutboxPort;
import com.personal.baton.application.identity.port.out.EmailVerificationOutboxPayloadProtector;
import com.personal.baton.application.identity.port.out.EmailVerificationOutboxPayloadProtector.PlainPayload;
import com.personal.baton.application.identity.port.out.EmailVerificationOutboxPayloadProtector.ProtectedPayload;
import com.personal.baton.application.identity.port.out.EmailVerificationOutboxPayloadProtector.ProtectionContext;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmailVerificationOutboxDispatchServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-08T01:02:03Z");
    private static final PlainPayload PLAIN_PAYLOAD = new PlainPayload(
            "study.user@example.com",
            "secure-email-verification-token-000000000001"
    );

    @DisplayName("현재 challenge와 lease가 유효한 이메일 인증 메시지만 전달 완료한다")
    @Test
    void deliversCurrentClaimAfterClaimTransaction() {
        EmailVerificationOutboxPort outboxPort = mock(EmailVerificationOutboxPort.class);
        EmailVerificationDeliveryPort deliveryPort = mock(EmailVerificationDeliveryPort.class);
        EmailVerificationOutboxDelivery delivery = delivery(1, 1);
        when(outboxPort.claimPending(anyInt(), eq(NOW), any())).thenReturn(List.of(delivery));
        when(outboxPort.isClaimCurrent(1, delivery.leaseToken(), NOW)).thenReturn(true);
        when(outboxPort.markDelivered(1, delivery.leaseToken(), NOW)).thenReturn(true);

        var result = service(outboxPort, deliveryPort).dispatchPending();

        assertThat(result.deliveredCount()).isOne();
        assertThat(result.failedCount()).isZero();
        verify(deliveryPort).deliver(new EmailVerificationDelivery(
                delivery.protectionContext().accountId(),
                PLAIN_PAYLOAD.email(),
                PLAIN_PAYLOAD.verificationToken(),
                delivery.protectionContext().expiresAt()
        ));
        verify(outboxPort).markDelivered(1, delivery.leaseToken(), NOW);
    }

    @DisplayName("재발급으로 supersede된 claim은 SMTP 호출 직전에 걸러낸다")
    @Test
    void skipsSupersededClaimBeforeDelivery() {
        EmailVerificationOutboxPort outboxPort = mock(EmailVerificationOutboxPort.class);
        EmailVerificationDeliveryPort deliveryPort = mock(EmailVerificationDeliveryPort.class);
        EmailVerificationOutboxDelivery delivery = delivery(2, 1);
        when(outboxPort.claimPending(anyInt(), eq(NOW), any())).thenReturn(List.of(delivery));
        when(outboxPort.isClaimCurrent(2, delivery.leaseToken(), NOW)).thenReturn(false);

        var result = service(outboxPort, deliveryPort).dispatchPending();

        assertThat(result.supersededCount()).isOne();
        verify(deliveryPort, never()).deliver(any());
        verify(outboxPort, never()).markDelivered(anyInt(), any(), any());
    }

    @DisplayName("일시적인 메일 전달 실패는 토큰을 로그에 노출하지 않고 지수 backoff로 재시도한다")
    @Test
    void retriesDeliveryFailureWithBoundedBackoff() {
        EmailVerificationOutboxPort outboxPort = mock(EmailVerificationOutboxPort.class);
        EmailVerificationDeliveryPort deliveryPort = mock(EmailVerificationDeliveryPort.class);
        EmailVerificationOutboxDelivery delivery = delivery(3, 1);
        when(outboxPort.claimPending(anyInt(), eq(NOW), any())).thenReturn(List.of(delivery));
        when(outboxPort.isClaimCurrent(3, delivery.leaseToken(), NOW)).thenReturn(true);
        doThrow(new EmailVerificationDeliveryUnavailableException("SMTP unavailable"))
                .when(deliveryPort).deliver(any());

        var result = service(outboxPort, deliveryPort).dispatchPending();

        assertThat(result.failedCount()).isOne();
        verify(outboxPort).markRetry(
                3,
                delivery.leaseToken(),
                NOW.plusSeconds(30),
                "EMAIL_DELIVERY_UNAVAILABLE"
        );
    }

    @DisplayName("여덟 번째 메일 전달 실패는 무한 재시도 대신 terminal failure로 남긴다")
    @Test
    void stopsAfterMaximumAttempts() {
        EmailVerificationOutboxPort outboxPort = mock(EmailVerificationOutboxPort.class);
        EmailVerificationDeliveryPort deliveryPort = mock(EmailVerificationDeliveryPort.class);
        EmailVerificationOutboxDelivery delivery = delivery(4, 8);
        when(outboxPort.claimPending(anyInt(), eq(NOW), any())).thenReturn(List.of(delivery));
        when(outboxPort.isClaimCurrent(4, delivery.leaseToken(), NOW)).thenReturn(true);
        doThrow(new EmailVerificationDeliveryUnavailableException("disabled"))
                .when(deliveryPort).deliver(any());

        service(outboxPort, deliveryPort).dispatchPending();

        verify(outboxPort).markFailed(
                4,
                delivery.leaseToken(),
                NOW,
                "EMAIL_DELIVERY_UNAVAILABLE"
        );
        verify(outboxPort, never()).markRetry(anyInt(), any(), any(), any());
    }

    @DisplayName("변조된 ciphertext는 SMTP에 전달하지 않고 즉시 terminal failure로 닫는다")
    @Test
    void rejectsTamperedPayloadWithoutDelivery() {
        EmailVerificationOutboxPort outboxPort = mock(EmailVerificationOutboxPort.class);
        EmailVerificationDeliveryPort deliveryPort = mock(EmailVerificationDeliveryPort.class);
        EmailVerificationOutboxPayloadProtector payloadProtector = mock(
                EmailVerificationOutboxPayloadProtector.class
        );
        EmailVerificationOutboxDelivery delivery = delivery(5, 1);
        when(outboxPort.claimPending(anyInt(), eq(NOW), any())).thenReturn(List.of(delivery));
        when(outboxPort.isClaimCurrent(5, delivery.leaseToken(), NOW)).thenReturn(true);
        when(payloadProtector.unprotect(any(), any())).thenThrow(
                new EmailVerificationPayloadProtectionException("invalid", false)
        );

        service(outboxPort, payloadProtector, deliveryPort).dispatchPending();

        verify(deliveryPort, never()).deliver(any());
        verify(outboxPort).markFailed(
                5,
                delivery.leaseToken(),
                NOW,
                "EMAIL_PAYLOAD_INVALID"
        );
    }

    @DisplayName("암호화 키를 일시적으로 읽을 수 없으면 ciphertext를 보존하고 backoff 재시도한다")
    @Test
    void retriesWhenProtectionKeyIsUnavailable() {
        EmailVerificationOutboxPort outboxPort = mock(EmailVerificationOutboxPort.class);
        EmailVerificationDeliveryPort deliveryPort = mock(EmailVerificationDeliveryPort.class);
        EmailVerificationOutboxPayloadProtector payloadProtector = mock(
                EmailVerificationOutboxPayloadProtector.class
        );
        EmailVerificationOutboxDelivery delivery = delivery(6, 1);
        when(outboxPort.claimPending(anyInt(), eq(NOW), any())).thenReturn(List.of(delivery));
        when(outboxPort.isClaimCurrent(6, delivery.leaseToken(), NOW)).thenReturn(true);
        when(payloadProtector.unprotect(any(), any())).thenThrow(
                new EmailVerificationPayloadProtectionException("missing key", true)
        );

        service(outboxPort, payloadProtector, deliveryPort).dispatchPending();

        verify(deliveryPort, never()).deliver(any());
        verify(outboxPort).markRetry(
                6,
                delivery.leaseToken(),
                NOW.plusSeconds(30),
                "EMAIL_PAYLOAD_KEY_UNAVAILABLE"
        );
    }

    private EmailVerificationOutboxDispatchService service(
            EmailVerificationOutboxPort outboxPort,
            EmailVerificationDeliveryPort deliveryPort
    ) {
        EmailVerificationOutboxPayloadProtector payloadProtector = mock(
                EmailVerificationOutboxPayloadProtector.class
        );
        when(payloadProtector.unprotect(any(), any())).thenReturn(PLAIN_PAYLOAD);
        return service(outboxPort, payloadProtector, deliveryPort);
    }

    private EmailVerificationOutboxDispatchService service(
            EmailVerificationOutboxPort outboxPort,
            EmailVerificationOutboxPayloadProtector payloadProtector,
            EmailVerificationDeliveryPort deliveryPort
    ) {
        return new EmailVerificationOutboxDispatchService(
                outboxPort,
                payloadProtector,
                deliveryPort,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private EmailVerificationOutboxDelivery delivery(long id, int attemptCount) {
        return new EmailVerificationOutboxDelivery(
                id,
                new ProtectionContext(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "a".repeat(64),
                        NOW.plusSeconds(1800)
                ),
                new ProtectedPayload(
                        "encryptedPayloadValue000000000000000000000000000000",
                        "nonceValue000000"
                ),
                attemptCount,
                UUID.randomUUID()
        );
    }
}
