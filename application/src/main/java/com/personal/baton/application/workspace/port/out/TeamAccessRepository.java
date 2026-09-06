package com.personal.baton.application.workspace.port.out;

import com.personal.baton.domain.roundauth.AccountTeamMembership;
import com.personal.baton.application.workspace.port.in.TeamAccessUseCase.MyTeamResult;
import com.personal.baton.domain.workspace.TeamInvitation;
import com.personal.baton.domain.workspace.TeamAccessAudit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TeamAccessRepository {
    List<MyTeamResult> findAccountTeams(UUID accountId);
    List<UUID> findAccountMembershipTeamIds(UUID accountId);
    List<AccountTeamMembership> lockAccountMemberships(UUID accountId);
    List<AccountTeamMembership> findMemberships(UUID teamId);
    Optional<AccountTeamMembership> findMembershipByMemberId(UUID memberId);
    boolean existsOtherActiveAdministrator(UUID teamId, UUID memberId);
    AccountTeamMembership saveMembership(AccountTeamMembership membership);
    Optional<UUID> findInvitationTeamId(String tokenHash);
    Optional<TeamInvitation> lockInvitation(String tokenHash);
    Optional<TeamInvitation> findInvitationByTokenHash(String tokenHash);
    Optional<TeamInvitation> findInvitation(UUID invitationId);
    List<TeamInvitation> findInvitations(UUID teamId);
    List<TeamInvitation> findUnacceptedInvitations(UUID teamId, UUID memberId);
    TeamInvitation saveInvitation(TeamInvitation invitation);
    void saveAudit(TeamAccessAudit audit);
    List<TeamAccessAudit> findAudit(UUID teamId);
}
