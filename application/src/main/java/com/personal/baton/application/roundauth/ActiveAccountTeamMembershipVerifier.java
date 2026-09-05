package com.personal.baton.application.roundauth;

import com.personal.baton.application.roundauth.error.RoundParticipationDeniedException;
import com.personal.baton.application.identity.port.out.IdentityRepository;
import com.personal.baton.domain.identity.Account;
import com.personal.baton.application.roundauth.port.out.RoundAuthorizationRepository;
import com.personal.baton.application.workspace.port.out.WorkspacePeopleRepository;
import com.personal.baton.domain.roundauth.AccountTeamMembership;
import com.personal.baton.domain.workspace.Member;
import com.personal.baton.application.workspace.port.out.WorkspaceAccessRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public final class ActiveAccountTeamMembershipVerifier {

    private final RoundAuthorizationRepository roundRepository;
    private final WorkspacePeopleRepository peopleRepository;
    private final WorkspaceAccessRepository accessRepository;
    private final IdentityRepository identities;

    public ActiveAccountTeamMembershipVerifier(
            RoundAuthorizationRepository roundRepository,
            WorkspacePeopleRepository peopleRepository,
            WorkspaceAccessRepository accessRepository,
            IdentityRepository identities
    ) {
        this.roundRepository = roundRepository;
        this.peopleRepository = peopleRepository;
        this.accessRepository = accessRepository;
        this.identities = identities;
    }

    public boolean hasActiveMembership(UUID accountId, UUID teamId) {
        if (identities.findAccountById(accountId).filter(Account::isActive).isEmpty()) return false;
        var team = accessRepository.findTeamById(teamId);
        if (team.isEmpty()) return false;
        return roundRepository.findMembership(accountId, teamId)
                .filter(membership -> !team.get().isAccountAccessEnabled() || membership.getPermission() != null)
                .map(AccountTeamMembership::getMemberId)
                .flatMap(peopleRepository::findMemberById)
                .filter(member -> member.getTeamId().equals(teamId))
                .filter(Member::isActive)
                .isPresent();
    }

    void requireActive(UUID accountId, UUID teamId) {
        if (!hasActiveMembership(accountId, teamId)) {
            throw new RoundParticipationDeniedException();
        }
    }
}
