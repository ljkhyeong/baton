package com.personal.baton.application.identity.port.in;

import com.personal.baton.application.identity.AccountView;
import java.time.Instant;

public interface RegisterLocalAccountUseCase {

    LocalRegistrationResult registerLocalAccount(RegisterLocalAccountCommand command);

    record RegisterLocalAccountCommand(
            String email,
            String displayName
    ) {

        @Override
        public String toString() {
            return "RegisterLocalAccountCommand["
                    + "email=[REDACTED], displayName=[REDACTED]]";
        }
    }

    record LocalRegistrationResult(
            AccountView account,
            Instant verificationExpiresAt
    ) {
    }
}
