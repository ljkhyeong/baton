package com.personal.baton.application.identity;

import com.personal.baton.domain.identity.Account;
import com.personal.baton.domain.identity.AccountIdentity;
import com.personal.baton.domain.identity.IdentityProvider;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public record AccountView(
        UUID accountId,
        String displayName,
        List<LinkedIdentityView> identities
) {

    public AccountView {
        identities = List.copyOf(identities);
    }

    static AccountView from(Account account, List<AccountIdentity> identities) {
        List<LinkedIdentityView> linkedIdentities = identities.stream()
                .sorted(Comparator.comparing(identity -> identity.getProvider().name()))
                .map(identity -> new LinkedIdentityView(
                        identity.getProvider(),
                        identity.getEmailSnapshot(),
                        identity.isEmailVerified()
                ))
                .toList();
        return new AccountView(account.getId(), account.getDisplayName(), linkedIdentities);
    }

    public record LinkedIdentityView(
            IdentityProvider provider,
            String email,
            boolean emailVerified
    ) {
    }
}
