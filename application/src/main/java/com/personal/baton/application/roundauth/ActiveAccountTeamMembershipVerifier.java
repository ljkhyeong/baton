package com.personal.baton.application.roundauth;

import com.personal.baton.application.roundauth.error.RoundParticipationDeniedException;
import com.personal.baton.application.roundauth.port.out.RoundAuthorizationRepository;
import com.personal.baton.application.workspace.port.out.WorkspacePeopleRepository;
import com.personal.baton.domain.roundauth.AccountTeamMembership;
import com.personal.baton.domain.workspace.Member;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public final class ActiveAccountTeamMembershipVerifier {

    private final RoundAuthorizationRepository roundRepository;
    private final WorkspacePeopleRepository peopleRepository;

    public ActiveAccountTeamMembershipVerifier(
            RoundAuthorizationRepository roundRepository,
            WorkspacePeopleRepository peopleRepository
    ) {
        this.roundRepository = roundRepository;
        this.peopleRepository = peopleRepository;
    }

    public boolean hasActiveMembership(UUID accountId, UUID teamId) {
        return roundRepository.findMembership(accountId, teamId)
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
