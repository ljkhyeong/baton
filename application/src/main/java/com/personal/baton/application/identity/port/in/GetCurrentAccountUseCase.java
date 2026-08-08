package com.personal.baton.application.identity.port.in;

import com.personal.baton.application.identity.AccountView;
import java.util.UUID;

public interface GetCurrentAccountUseCase {

    AccountView getCurrentAccount(UUID accountId);
}
