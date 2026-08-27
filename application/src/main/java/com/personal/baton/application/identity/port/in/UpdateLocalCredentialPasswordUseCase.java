package com.personal.baton.application.identity.port.in;

import java.util.UUID;

public interface UpdateLocalCredentialPasswordUseCase {

    void updateLocalCredentialPassword(UpdateLocalCredentialPasswordCommand command);

    record UpdateLocalCredentialPasswordCommand(
            UUID accountId,
            String encodedPassword
    ) {

        @Override
        public String toString() {
            return "UpdateLocalCredentialPasswordCommand[accountId=" + accountId
                    + ", encodedPassword=[REDACTED]]";
        }
    }
}
