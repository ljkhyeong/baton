package com.personal.baton.application.identity.port.in;

import com.personal.baton.application.identity.AccountView;
import java.util.UUID;

public interface AccountSecurityUseCase {

    AccountView getAccount(UUID accountId);

    void changeLocalPassword(ChangeLocalPasswordCommand command);

    void revokeAllSessions(UUID accountId);

    record ChangeLocalPasswordCommand(
            UUID accountId,
            String currentRawPassword,
            String newRawPassword
    ) {
        @Override
        public String toString() {
            return "ChangeLocalPasswordCommand[accountId=" + accountId
                    + ", currentRawPassword=[REDACTED], newRawPassword=[REDACTED]]";
        }
    }
}
