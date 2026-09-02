package com.personal.baton.application.roundauth;

import com.personal.baton.application.roundauth.error.RoundParticipationDeniedException;
import com.personal.baton.application.roundauth.port.out.RoundAuthorizationRepository;
import com.personal.baton.application.workspace.port.out.WorkspacePeopleRepository;
import com.personal.baton.domain.roundauth.AccountTeamMembership;
import com.personal.baton.domain.workspace.Member;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
final class RoundMembershipVerifier {

    private final RoundAuthorizationRepository roundRepository;
    private final WorkspacePeopleRepository peopleRepository;

    RoundMembershipVerifier(
            RoundAuthorizationRepository roundRepository,
            WorkspacePeopleRepository peopleRepository
    ) {
        this.roundRepository = roundRepository;
        this.peopleRepository = peopleRepository;
    }

    void requireActive(UUID accountId, UUID teamId) {
        AccountTeamMembership membership = roundRepository
                .findMembership(accountId, teamId)
                .orElseThrow(RoundParticipationDeniedException::new);
        boolean activeMember = peopleRepository.findMemberById(membership.getMemberId())
                .filter(member -> member.getTeamId().equals(teamId))
                .filter(Member::isActive)
                .isPresent();
        if (!activeMember) {
            throw new RoundParticipationDeniedException();
        }
    }
}
