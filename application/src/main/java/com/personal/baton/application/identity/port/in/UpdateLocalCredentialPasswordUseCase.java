package com.personal.baton.application.identity.port.in;

import java.util.UUID;

public interface UpdateLocalCredentialPasswordUseCase {

    void updateLocalCredentialPassword(UpdateLocalCredentialPasswordCommand command);

    record UpdateLocalCredentialPasswordCommand(
            UUID accountId,
            String expectedPasswordHash,
            String encodedPassword
    ) {

        @Override
        public String toString() {
            return "UpdateLocalCredentialPasswordCommand[accountId=" + accountId
                    + ", expectedPasswordHash=[REDACTED], encodedPassword=[REDACTED]]";
        }
    }
}
