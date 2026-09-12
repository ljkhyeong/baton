package com.personal.baton.application.identity;

import com.personal.baton.application.identity.error.HumanVerificationRejectedException;
import com.personal.baton.application.identity.error.HumanVerificationUnavailableException;
import com.personal.baton.application.identity.port.in.HumanVerificationUseCase;
import com.personal.baton.application.identity.port.out.HumanVerificationPort;
import com.personal.baton.application.identity.port.out.HumanVerificationPort.HumanVerificationAttempt;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class HumanVerificationService implements HumanVerificationUseCase {

    private final HumanVerificationPort verificationPort;

    public HumanVerificationService(HumanVerificationPort verificationPort) {
        this.verificationPort = verificationPort;
    }

    @Override
    public Optional<String> siteKey() {
        return verificationPort.siteKey();
    }

    @Override
    public void verify(HumanVerificationCommand command) {
        Objects.requireNonNull(command, "자동 요청 방지 검증 명령은 필수입니다");
        var outcome = verificationPort.verify(new HumanVerificationAttempt(
                command.token(),
                command.remoteAddress(),
                Objects.requireNonNull(command.action(), "검증 동작은 필수입니다")
                        .externalValue()
        ));
        switch (outcome) {
            case VERIFIED -> {
            }
            case REJECTED -> throw new HumanVerificationRejectedException();
            case UNAVAILABLE -> throw new HumanVerificationUnavailableException();
        }
    }
}
