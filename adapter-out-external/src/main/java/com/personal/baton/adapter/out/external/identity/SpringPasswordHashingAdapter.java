package com.personal.baton.adapter.out.external.identity;

import com.personal.baton.application.identity.port.out.PasswordHashingPort;
import java.util.Objects;
import org.springframework.security.crypto.password.PasswordEncoder;

public final class SpringPasswordHashingAdapter implements PasswordHashingPort {

    private final PasswordEncoder passwordEncoder;

    public SpringPasswordHashingAdapter(PasswordEncoder passwordEncoder) {
        this.passwordEncoder = Objects.requireNonNull(
                passwordEncoder,
                "비밀번호 인코더는 필수입니다"
        );
    }

    @Override
    public String encode(String rawPassword) {
        return passwordEncoder.encode(Objects.requireNonNull(
                rawPassword,
                "원문 비밀번호는 필수입니다"
        ));
    }
}
