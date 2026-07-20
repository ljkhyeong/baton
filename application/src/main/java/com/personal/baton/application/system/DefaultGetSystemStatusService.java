package com.personal.baton.application.system;

import com.personal.baton.application.system.port.in.GetSystemStatusUseCase;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;

@Service
public class DefaultGetSystemStatusService implements GetSystemStatusUseCase {

    private final Clock clock;

    public DefaultGetSystemStatusService(Clock clock) {
        this.clock = clock;
    }

    @Override
    public SystemStatusResult getStatus() {
        return new SystemStatusResult("baton", Instant.now(clock));
    }
}
