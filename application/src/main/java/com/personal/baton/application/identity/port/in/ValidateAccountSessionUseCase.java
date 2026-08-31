package com.personal.baton.application.identity.port.in;

import java.util.UUID;

public interface ValidateAccountSessionUseCase {
    boolean isAccountSessionCurrent(UUID accountId, long sessionVersion);
}
