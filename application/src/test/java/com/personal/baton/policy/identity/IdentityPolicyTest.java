package com.personal.baton.policy.identity;

import com.personal.baton.domain.identity.AccountIdentity;
import com.personal.baton.domain.identity.EmailVerificationChallenge;
import com.personal.baton.domain.identity.IdentityProvider;
import com.personal.baton.domain.identity.IdentityValidationException;
import com.personal.baton.domain.identity.LocalCredential;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("policy")
class IdentityPolicyTest {

    private static final Instant NOW = Instant.parse("2026-08-08T01:02:03Z");

    @DisplayName("자체 이메일 신원은 공백을 제거하고 소문자화하지만 공급자 별칭은 추측하지 않는다")
    @Test
    void normalizesOnlyLocalEmailCaseAndWhitespace() {
        UUID accountId = UUID.randomUUID();

        AccountIdentity identity = AccountIdentity.createLocal(
                UUID.randomUUID(),
                accountId,
                "  Study.User+Round@Example.COM  ",
                NOW
        );

        assertThat(identity.getProvider()).isEqualTo(IdentityProvider.LOCAL_EMAIL);
        assertThat(identity.getProviderSubject()).isEqualTo("study.user+round@example.com");
        assertThat(identity.getEmailSnapshot()).isEqualTo("study.user+round@example.com");
        assertThat(identity.isEmailVerified()).isFalse();
    }

    @DisplayName("외부 provider subject는 대소문자를 보존하고 앞뒤 공백을 별도 신원으로 정규화하지 않는다")
    @Test
    void preservesExternalSubjectAndRejectsWhitespaceAliasing() {
        AccountIdentity identity = AccountIdentity.createExternal(
                UUID.randomUUID(),
                UUID.randomUUID(),
                IdentityProvider.GOOGLE,
                "CaseSensitiveSubject",
                "USER@EXAMPLE.COM",
                true,
                NOW
        );

        assertThat(identity.getProviderSubject()).isEqualTo("CaseSensitiveSubject");
        assertThat(identity.getEmailSnapshot()).isEqualTo("user@example.com");
        assertThatThrownBy(() -> AccountIdentity.createExternal(
                UUID.randomUUID(),
                UUID.randomUUID(),
                IdentityProvider.GOOGLE,
                " CaseSensitiveSubject ",
                "user@example.com",
                true,
                NOW
        )).isInstanceOf(IdentityValidationException.class);
    }

    @DisplayName("외부 로그인 갱신은 이메일 snapshot이 없을 때 기존 검증 정보를 지우지 않는다")
    @Test
    void keepsExistingEmailSnapshotWhenProviderOmitsEmail() {
        AccountIdentity identity = AccountIdentity.createExternal(
                UUID.randomUUID(),
                UUID.randomUUID(),
                IdentityProvider.NAVER,
                "naver-user-id",
                "user@example.com",
                true,
                NOW
        );

        identity.recordExternalAuthentication(null, false, NOW.plusSeconds(60));

        assertThat(identity.getEmailSnapshot()).isEqualTo("user@example.com");
        assertThat(identity.isEmailVerified()).isTrue();
        assertThat(identity.getLastAuthenticatedAt()).isEqualTo(NOW.plusSeconds(60));
    }

    @DisplayName("외부 제공자가 이메일 없이 검증 여부만 반환해도 검증된 이메일로 저장하지 않는다")
    @Test
    void doesNotMarkMissingExternalEmailAsVerified() {
        AccountIdentity identity = AccountIdentity.createExternal(
                UUID.randomUUID(),
                UUID.randomUUID(),
                IdentityProvider.NAVER,
                "naver-without-email",
                null,
                true,
                NOW
        );

        assertThat(identity.getEmailSnapshot()).isNull();
        assertThat(identity.isEmailVerified()).isFalse();
    }

    @DisplayName("이메일 인증 도전은 만료 전 한 번만 소비되고 정확한 만료 시각부터 거절된다")
    @Test
    void consumesVerificationChallengeOnceBeforeExclusiveExpiry() {
        EmailVerificationChallenge challenge = EmailVerificationChallenge.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "a".repeat(64),
                NOW,
                NOW.plusSeconds(60)
        );

        assertThat(challenge.consume(NOW.plusSeconds(59))).isTrue();
        assertThat(challenge.consume(NOW.plusSeconds(59))).isFalse();
        assertThat(challenge.getConsumedAt()).isEqualTo(NOW.plusSeconds(59));

        EmailVerificationChallenge expiring = EmailVerificationChallenge.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "b".repeat(64),
                NOW,
                NOW.plusSeconds(60)
        );
        assertThat(expiring.consume(NOW.plusSeconds(60))).isFalse();
        assertThat(expiring.consume(NOW.minusNanos(1))).isFalse();
        assertThat(expiring.getConsumedAt()).isNull();
    }

    @DisplayName("미소비 이메일 인증 도전은 새 해시와 만료로 재발급하고 소비 뒤에는 재발급하지 않는다")
    @Test
    void reissuesOnlyUnconsumedVerificationChallenge() {
        EmailVerificationChallenge challenge = EmailVerificationChallenge.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "a".repeat(64),
                NOW,
                NOW.plusSeconds(60)
        );

        challenge.reissue("b".repeat(64), NOW.plusSeconds(1), NOW.plusSeconds(121));

        assertThat(challenge.getTokenHash()).isEqualTo("b".repeat(64));
        assertThat(challenge.getCreatedAt()).isEqualTo(NOW.plusSeconds(1));
        assertThat(challenge.getExpiresAt()).isEqualTo(NOW.plusSeconds(121));
        assertThat(challenge.consume(NOW.plusSeconds(2))).isTrue();
        assertThatThrownBy(() -> challenge.reissue(
                "c".repeat(64),
                NOW.plusSeconds(3),
                NOW.plusSeconds(123)
        )).isInstanceOf(IdentityValidationException.class);
        assertThat(challenge.getTokenHash()).isEqualTo("b".repeat(64));
    }

    @DisplayName("로컬 자격 증명 hash 교체는 수정 시각을 단조 증가시키고 실패하면 기존 값을 보존한다")
    @Test
    void replacesPasswordHashWithMonotonicUpdateTime() {
        LocalCredential credential = LocalCredential.create(
                UUID.randomUUID(),
                "{bcrypt}$2a$10$opaque-encoded-password-value",
                NOW
        );

        credential.replacePasswordHash(
                "{pbkdf2@SpringSecurity_v5_8}upgraded-opaque-password-value",
                NOW.plusSeconds(1)
        );

        assertThatThrownBy(() -> credential.replacePasswordHash(
                "{bcrypt}$2a$10$stale-opaque-password-value",
                NOW
        )).isInstanceOf(IdentityValidationException.class);
        assertThat(credential.getPasswordHash())
                .isEqualTo("{pbkdf2@SpringSecurity_v5_8}upgraded-opaque-password-value");
        assertThat(credential.getCreatedAt()).isEqualTo(NOW);
        assertThat(credential.getUpdatedAt()).isEqualTo(NOW.plusSeconds(1));
    }

}
