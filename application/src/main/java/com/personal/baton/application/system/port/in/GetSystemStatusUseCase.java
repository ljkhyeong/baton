package com.personal.baton.application.system.port.in;

import java.time.Instant;

public interface GetSystemStatusUseCase {

    SystemStatusResult getStatus();

    record SystemStatusResult(String service, Instant checkedAt) {
    }
}
