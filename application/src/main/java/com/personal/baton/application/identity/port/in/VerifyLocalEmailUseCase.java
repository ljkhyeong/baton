package com.personal.baton.application.identity.port.in;

public interface VerifyLocalEmailUseCase {

    void verifyLocalEmail(VerifyLocalEmailCommand command);

    record VerifyLocalEmailCommand(
            String verificationToken,
            String rawPassword
    ) {

        @Override
        public String toString() {
            return "VerifyLocalEmailCommand["
                    + "verificationToken=[REDACTED], rawPassword=[REDACTED]]";
        }
    }
}
