package com.personal.baton.application.identity.port.in;

import java.util.Optional;

public interface HumanVerificationUseCase {

    Optional<String> siteKey();

    void verify(HumanVerificationCommand command);

    record HumanVerificationCommand(
            String token,
            String remoteAddress,
            Action action
    ) {
    }

    enum Action {
        LOCAL_REGISTRATION("local_registration"),
        PASSWORD_RESET_REQUEST("password_reset_request");

        private final String externalValue;

        Action(String externalValue) {
            this.externalValue = externalValue;
        }

        public String externalValue() {
            return externalValue;
        }
    }
}
