package com.personal.baton.application.roundauth;

import com.personal.baton.application.roundauth.error.RoundParticipationDeniedException;
import com.personal.baton.application.identity.port.out.IdentityRepository;
import com.personal.baton.domain.identity.Account;
import com.personal.baton.application.roundauth.port.out.RoundAuthorizationRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public final class ActiveAccountTeamMembershipVerifier {

    private final RoundAuthorizationRepository roundRepository;
    private final IdentityRepository identities;

    public ActiveAccountTeamMembershipVerifier(
            RoundAuthorizationRepository roundRepository,
            IdentityRepository identities
    ) {
        this.roundRepository = roundRepository;
        this.identities = identities;
    }

    public boolean hasActiveMembership(UUID accountId, UUID teamId) {
        if (identities.findAccountById(accountId).filter(Account::isActive).isEmpty()) return false;
        return roundRepository.existsActiveTeamMembership(accountId, teamId);
    }

    void requireActive(UUID accountId, UUID teamId) {
        if (!hasActiveMembership(accountId, teamId)) {
            throw new RoundParticipationDeniedException();
        }
    }
}
