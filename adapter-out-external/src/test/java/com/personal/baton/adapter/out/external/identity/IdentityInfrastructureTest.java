package com.personal.baton.adapter.out.external.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.personal.baton.application.identity.error.EmailVerificationDeliveryUnavailableException;
import com.personal.baton.application.identity.port.out.EmailVerificationDeliveryPort.EmailVerificationDelivery;
import java.net.URI;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSender;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class IdentityInfrastructureTest {

    @Test
    @DisplayName("12자와 128자 Unicode 비밀번호 경계는 PBKDF2 위임 형식으로 저장한다")
    void hashesPasswordWithDelegatingEncoder() {
        var encoder = new IdentityInfrastructureConfig().passwordEncoder();
        String minimumUnicodePassword = "가".repeat(12);
        String maximumUnicodePassword = "힣".repeat(128);

        String minimumEncoded = encoder.encode(minimumUnicodePassword);
        String maximumEncoded = encoder.encode(maximumUnicodePassword);

        assertThat(minimumEncoded).startsWith("{pbkdf2@SpringSecurity_v5_8}");
        assertThat(maximumEncoded).startsWith("{pbkdf2@SpringSecurity_v5_8}");
        assertThat(encoder.matches(minimumUnicodePassword, minimumEncoded)).isTrue();
        assertThat(encoder.matches(maximumUnicodePassword, maximumEncoded)).isTrue();
        assertThat(encoder.matches("wrong password", maximumEncoded)).isFalse();
        assertThat(encoder.upgradeEncoding(maximumEncoded)).isFalse();
    }

    @Test
    @DisplayName("새 PBKDF2 인코더는 기존 bcrypt 위임 해시도 계속 검증한다")
    void verifiesLegacyBcryptHashes() {
        var encoder = new IdentityInfrastructureConfig().passwordEncoder();
        var bcrypt = new BCryptPasswordEncoder();
        String rawPassword = "correct horse battery staple";
        String legacyHash = "{bcrypt}" + bcrypt.encode(rawPassword);

        assertThat(encoder.matches(rawPassword, legacyHash)).isTrue();
        assertThat(encoder.matches("wrong password", legacyHash)).isFalse();
        assertThat(encoder.upgradeEncoding(legacyHash)).isTrue();
    }

    @Test
    @DisplayName("이메일 인증 토큰은 256비트 URL-safe 난수로 생성한다")
    void generatesUrlSafeVerificationTokens() {
        var generator = new IdentityInfrastructureConfig().verificationTokenGenerator();

        String token = generator.generateKey();

        assertThat(token).doesNotContain("=");
        assertThat(Base64.getUrlDecoder().decode(token)).hasSize(32);
    }

    @Test
    @DisplayName("메일 발송을 설정하지 않으면 가입을 성공시킨 척하지 않는다")
    void disabledDeliveryFailsClosed() {
        var adapter = new DisabledEmailVerificationDeliveryAdapter();

        assertThatThrownBy(() -> adapter.deliver(new EmailVerificationDelivery(
                UUID.randomUUID(),
                "member@example.com",
                "token",
                Instant.parse("2026-08-08T12:30:00Z")
        )))
                .isInstanceOf(EmailVerificationDeliveryUnavailableException.class)
                .hasMessageContaining("설정되지 않았습니다");
    }

    @Test
    @DisplayName("인증 메일은 링크에서 비밀번호를 설정하기 전까지 비밀번호가 없음을 안내한다")
    void explainsVerifiedFirstPasswordSetup() {
        MailSender mailSender = mock(MailSender.class);
        var adapter = new SmtpEmailVerificationDeliveryAdapter(
                mailSender,
                "no-reply@b4ton.com",
                URI.create("https://manager.b4ton.com")
        );

        adapter.deliver(new EmailVerificationDelivery(
                UUID.randomUUID(),
                "member@example.com",
                "secure-token-value",
                Instant.parse("2026-08-08T12:30:00Z")
        ));

        ArgumentCaptor<SimpleMailMessage> messageCaptor =
                ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(messageCaptor.capture());
        assertThat(messageCaptor.getValue().getText())
                .contains("링크에서 이메일을 확인하고 비밀번호를 설정해야 가입이 완료됩니다")
                .contains("이 링크를 사용하기 전에는 비밀번호가 설정되지 않습니다")
                .contains("https://manager.b4ton.com/verify-email#token=secure-token-value");
    }
}
