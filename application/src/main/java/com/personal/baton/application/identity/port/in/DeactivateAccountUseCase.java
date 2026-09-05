package com.personal.baton.application.identity.port.in;

import java.util.UUID;

public interface DeactivateAccountUseCase {
    void deactivateAccount(UUID accountId);
}
