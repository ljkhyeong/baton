package com.personal.baton.application.identity.port.out;

import java.util.Optional;
import java.util.UUID;

public interface CurrentAccountProvider {
    Optional<UUID> currentAccountId();
}
