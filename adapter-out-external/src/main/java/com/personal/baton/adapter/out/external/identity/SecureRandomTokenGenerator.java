package com.personal.baton.adapter.out.external.identity;

import com.personal.baton.application.identity.port.out.SecureTokenGeneratorPort;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;

public final class SecureRandomTokenGenerator implements SecureTokenGeneratorPort {

    private static final int TOKEN_BYTES = 32;

    private final SecureRandom secureRandom;

    public SecureRandomTokenGenerator() {
        this(new SecureRandom());
    }

    SecureRandomTokenGenerator(SecureRandom secureRandom) {
        this.secureRandom = Objects.requireNonNull(secureRandom, "안전한 난수 생성기는 필수입니다");
    }

    @Override
    public String generate() {
        byte[] entropy = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(entropy);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
    }
}
