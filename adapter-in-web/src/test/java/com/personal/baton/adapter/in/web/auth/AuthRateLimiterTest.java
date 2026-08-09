package com.personal.baton.adapter.in.web.auth;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthRateLimiterTest {

    @DisplayName("같은 이메일의 가입 요청은 시간 창 안에서 세 번까지만 허용한다")
    @Test
    void limitsRegistrationByNormalizedEmail() {
        AuthRateLimiter limiter = new AuthRateLimiter();

        limiter.checkRegistration("192.0.2.1", " Study.User@Example.COM ");
        limiter.checkRegistration("192.0.2.2", "study.user@example.com");
        limiter.checkRegistration("192.0.2.3", "STUDY.USER@example.com");

        assertThatThrownBy(() -> limiter.checkRegistration(
                "192.0.2.4",
                "study.user@example.com"
        )).isInstanceOf(AuthRateLimitExceededException.class);
    }

    @DisplayName("같은 IP와 이메일 조합의 로그인 무차별 대입은 열 번 뒤 제한한다")
    @Test
    void limitsLoginByIpAndEmail() {
        AuthRateLimiter limiter = new AuthRateLimiter();

        for (int attempt = 0; attempt < 10; attempt += 1) {
            limiter.checkLogin("198.51.100.1", "member@example.com");
        }

        assertThatThrownBy(() -> limiter.checkLogin(
                "198.51.100.1",
                "member@example.com"
        )).isInstanceOf(AuthRateLimitExceededException.class);
    }

    @DisplayName("IP를 회전한 같은 이메일 실패도 계정 축에서 스무 번 뒤 제한한다")
    @Test
    void limitsLoginFailuresByNormalizedAccountEmail() {
        AuthRateLimiter limiter = new AuthRateLimiter();

        for (int attempt = 0; attempt < 20; attempt += 1) {
            limiter.checkLogin(
                    "198.51.100." + (attempt + 1),
                    attempt % 2 == 0
                            ? " Member@Example.COM "
                            : "member@example.com"
            );
        }

        assertThatThrownBy(() -> limiter.checkLogin(
                "203.0.113.1",
                "member@example.com"
        )).isInstanceOf(AuthRateLimitExceededException.class)
                .satisfies(exception -> assertThat(
                        ((AuthRateLimitExceededException) exception).retryAfterSeconds()
                ).isBetween(1L, 60L));
    }

    @DisplayName("성공한 로그인은 유한한 계정 실패 예산을 즉시 복구한다")
    @Test
    void resetsAccountFailureBudgetAfterSuccess() {
        AuthRateLimiter limiter = new AuthRateLimiter();

        for (int attempt = 0; attempt < 20; attempt += 1) {
            limiter.checkLogin(
                    "198.51.100." + (attempt + 1),
                    "member@example.com"
            );
        }

        limiter.recordLoginSuccess(" MEMBER@example.com ");
        limiter.checkLogin("203.0.113.1", "member@example.com");
    }

    @DisplayName("로그인 IP 제한은 IPv6 주소 회전을 막도록 /64로 묶는다")
    @Test
    void limitsLoginByIpv6NetworkPrefix() {
        AuthRateLimiter limiter = new AuthRateLimiter();

        for (int attempt = 0; attempt < 60; attempt += 1) {
            limiter.checkLogin(
                    "2001:db8:abcd:42::" + Integer.toHexString(attempt + 1),
                    "member-" + attempt + "@example.com"
            );
        }

        assertThatThrownBy(() -> limiter.checkLogin(
                "2001:db8:abcd:42::ffff",
                "another@example.com"
        )).isInstanceOf(AuthRateLimitExceededException.class);
        limiter.checkLogin(
                "2001:db8:abcd:43::1",
                "another@example.com"
        );
    }

    @DisplayName("인증 token은 다섯 번 뒤 제한한다")
    @Test
    void limitsVerificationByToken() {
        AuthRateLimiter limiter = new AuthRateLimiter();
        String token = "opaque-verification-token-0000000000000001";

        for (int attempt = 0; attempt < 5; attempt += 1) {
            limiter.checkVerification("203.0.113." + attempt, token);
        }

        assertThatThrownBy(() -> limiter.checkVerification("203.0.113.200", token))
                .isInstanceOf(AuthRateLimitExceededException.class);
    }

    @DisplayName("ROUND refresh는 같은 Account와 room에서 분당 열두 번까지만 허용한다")
    @Test
    void limitsRoundGrantByAccountAndRoom() {
        AuthRateLimiter limiter = new AuthRateLimiter();
        UUID accountId = UUID.fromString("8e448211-66ae-44ab-9888-c4960648c22b");

        for (int attempt = 0; attempt < 12; attempt += 1) {
            limiter.checkRoundGrant(
                    "198.51.100." + (attempt + 1),
                    accountId,
                    "bcdf-ghjk-mnpq"
            );
        }

        assertThatThrownBy(() -> limiter.checkRoundGrant(
                "198.51.100.200",
                accountId,
                "bcdf-ghjk-mnpq"
        )).isInstanceOf(AuthRateLimitExceededException.class);
        limiter.checkRoundGrant("198.51.100.200", accountId, "cdef-hjkm-npqr");
    }

    @DisplayName("ROUND refresh의 IPv6 client 제한은 주소 회전을 막도록 /64로 묶는다")
    @Test
    void limitsRoundGrantByIpv6NetworkPrefix() {
        AuthRateLimiter limiter = new AuthRateLimiter();

        for (int attempt = 0; attempt < 120; attempt += 1) {
            limiter.checkRoundGrant(
                    "2001:db8:abcd:42::" + Integer.toHexString(attempt + 1),
                    new UUID(0, attempt + 1L),
                    "room-" + attempt
            );
        }

        assertThatThrownBy(() -> limiter.checkRoundGrant(
                "2001:db8:abcd:42::ffff",
                new UUID(0, 10_000),
                "another-room"
        )).isInstanceOf(AuthRateLimitExceededException.class);
        limiter.checkRoundGrant(
                "2001:db8:abcd:43::1",
                new UUID(0, 10_001),
                "another-room"
        );
    }
}
