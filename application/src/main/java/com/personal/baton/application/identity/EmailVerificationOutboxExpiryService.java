package com.personal.baton.application.identity;

import com.personal.baton.application.identity.port.in.ExpireEmailVerificationOutboxUseCase;
import com.personal.baton.application.identity.port.out.EmailVerificationOutboxPort;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;

@Service
public class EmailVerificationOutboxExpiryService
        implements ExpireEmailVerificationOutboxUseCase {

    private final EmailVerificationOutboxPort outboxPort;
    private final Clock clock;

    public EmailVerificationOutboxExpiryService(
            EmailVerificationOutboxPort outboxPort,
            Clock clock
    ) {
        this.outboxPort = outboxPort;
        this.clock = clock;
    }

    @Override
    public int expireUndeliverable() {
        return outboxPort.expireUndeliverable(Instant.now(clock));
    }
}
