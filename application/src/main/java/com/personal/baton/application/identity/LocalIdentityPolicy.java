package com.personal.baton.application.identity;

import com.personal.baton.domain.identity.IdentityValidationException;
import java.time.Duration;

final class LocalIdentityPolicy {

    static final Duration EMAIL_CHALLENGE_LIFETIME = Duration.ofMinutes(30);
    private static final int MINIMUM_PASSWORD_LENGTH = 12;
    private static final int MAXIMUM_PASSWORD_LENGTH = 128;

    private LocalIdentityPolicy() {
    }

    static void requireValidRawPassword(String rawPassword) {
        if (rawPassword == null
                || rawPassword.length() < MINIMUM_PASSWORD_LENGTH
                || rawPassword.length() > MAXIMUM_PASSWORD_LENGTH) {
            throw new IdentityValidationException(
                    "비밀번호는 " + MINIMUM_PASSWORD_LENGTH + "자 이상 "
                            + MAXIMUM_PASSWORD_LENGTH + "자 이하여야 합니다"
            );
        }
    }
}
