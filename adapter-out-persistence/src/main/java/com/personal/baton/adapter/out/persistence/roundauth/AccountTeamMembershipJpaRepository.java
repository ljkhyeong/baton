package com.personal.baton.adapter.out.persistence.roundauth;

import com.personal.baton.domain.roundauth.AccountTeamMembership;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import com.personal.baton.domain.workspace.TeamPermission;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountTeamMembershipJpaRepository
        extends JpaRepository<AccountTeamMembership, UUID> {

    @Query("""
            select t.id as teamId, t.name as teamName, m.id as memberId, m.name as memberName,
                   membership.permission as permission, s.id as seasonId, s.name as seasonName,
                   (s.endedAt is not null) as seasonEnded
            from AccountTeamMembership membership
            join Team t on t.id = membership.teamId
            join Member m on m.id = membership.memberId and m.teamId = t.id
            join Season s on s.teamId = t.id
            where membership.accountId = :accountId and membership.permission is not null
              and t.accountAccessEnabled = true and m.deactivatedAt is null
            order by t.name, t.id, case when s.endedAt is null then 0 else 1 end,
                     s.startDate desc, s.id desc
            """)
    List<AccountTeamSeason> findAccountTeamSeasons(UUID accountId);

    interface AccountTeamSeason {
        UUID getTeamId();
        String getTeamName();
        UUID getMemberId();
        String getMemberName();
        TeamPermission getPermission();
        UUID getSeasonId();
        String getSeasonName();
        boolean getSeasonEnded();
    }

    List<AccountTeamMembership> findAllByTeamId(UUID teamId);
    @Query("select membership.teamId from AccountTeamMembership membership where membership.accountId = :accountId")
    List<UUID> findTeamIdsByAccountId(UUID accountId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select membership from AccountTeamMembership membership where membership.accountId = :accountId")
    List<AccountTeamMembership> lockByAccountId(UUID accountId);

    Optional<AccountTeamMembership> findByAccountIdAndTeamId(UUID accountId, UUID teamId);

    Optional<AccountTeamMembership> findByMemberId(UUID memberId);

    @Query("""
            select count(membership) > 0
            from AccountTeamMembership membership
            join Member member on member.id = membership.memberId and member.teamId = membership.teamId
            where membership.teamId = :teamId and membership.memberId <> :memberId
              and membership.permission = com.personal.baton.domain.workspace.TeamPermission.ADMIN
              and member.deactivatedAt is null
            """)
    boolean existsOtherActiveAdministrator(UUID teamId, UUID memberId);

    @Query("""
            select count(membership) > 0
            from AccountTeamMembership membership
            join Team team on team.id = membership.teamId
            join Member member on member.id = membership.memberId and member.teamId = team.id
            where membership.accountId = :accountId and membership.teamId = :teamId
              and member.deactivatedAt is null
              and (team.accountAccessEnabled = false or membership.permission is not null)
            """)
    boolean existsActiveTeamMembership(UUID accountId, UUID teamId);
}
