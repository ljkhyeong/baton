package com.personal.baton.application.identity.port.in;

import com.personal.baton.application.identity.AccountView;
import java.time.Instant;

public interface VerifyLocalEmailUseCase {

    LocalEmailVerificationResult verifyLocalEmail(VerifyLocalEmailCommand command);

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

    record LocalEmailVerificationResult(
            AccountView account,
            Instant verifiedAt
    ) {
    }
}
