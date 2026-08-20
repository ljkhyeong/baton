package com.personal.baton.application.identity;

import com.personal.baton.application.crypto.DomainSeparatedSha256;
import com.personal.baton.domain.identity.IdentityValidationException;
import java.util.List;

final class VerificationTokenHash {

    private static final String DOMAIN = "baton:email-verification:v1";

    private VerificationTokenHash() {
    }

    static String hash(String token) {
        if (token == null || token.isBlank() || token.length() > 512) {
            throw new IdentityValidationException("이메일 인증 토큰 형식이 올바르지 않습니다");
        }
        return DomainSeparatedSha256.hashHex(DOMAIN, List.of(token));
    }
}
