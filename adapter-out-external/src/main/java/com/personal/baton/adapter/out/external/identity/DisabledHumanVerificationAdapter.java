package com.personal.baton.adapter.out.external.identity;

import com.personal.baton.application.identity.port.out.HumanVerificationPort;
import java.util.Optional;

public final class DisabledHumanVerificationAdapter implements HumanVerificationPort {

    @Override
    public Optional<String> siteKey() {
        return Optional.empty();
    }

    @Override
    public VerificationOutcome verify(HumanVerificationAttempt attempt) {
        return VerificationOutcome.VERIFIED;
    }
}
