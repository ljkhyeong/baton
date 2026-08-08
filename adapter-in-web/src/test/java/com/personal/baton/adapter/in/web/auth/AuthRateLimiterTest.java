package com.personal.baton.adapter.in.web.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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

    @DisplayName("공격자 IP의 실패가 다른 IP에서 로그인하는 계정을 잠그지 않는다")
    @Test
    void doesNotCreateAGlobalEmailLockout() {
        AuthRateLimiter limiter = new AuthRateLimiter();

        for (int attempt = 0; attempt < 10; attempt += 1) {
            limiter.checkLogin("198.51.100.1", "member@example.com");
        }

        limiter.checkLogin("198.51.100.2", "member@example.com");
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
}
