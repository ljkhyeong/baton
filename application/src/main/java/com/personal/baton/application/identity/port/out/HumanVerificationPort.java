package com.personal.baton.application.identity.port.out;

import java.util.Optional;

public interface HumanVerificationPort {

    Optional<String> siteKey();

    VerificationOutcome verify(HumanVerificationAttempt attempt);

    record HumanVerificationAttempt(
            String token,
            String remoteAddress,
            String expectedAction
    ) {
    }

    enum VerificationOutcome {
        VERIFIED,
        REJECTED,
        UNAVAILABLE
    }
}
