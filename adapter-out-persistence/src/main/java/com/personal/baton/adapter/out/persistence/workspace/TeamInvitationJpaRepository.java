package com.personal.baton.adapter.out.persistence.workspace;

import com.personal.baton.domain.workspace.TeamInvitation;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TeamInvitationJpaRepository extends JpaRepository<TeamInvitation, UUID> {
    List<TeamInvitation> findAllByTeamIdOrderByCreatedAtDesc(UUID teamId);
    List<TeamInvitation> findAllByTeamIdAndMemberIdAndAcceptedAtIsNullAndRevokedAtIsNull(UUID teamId, UUID memberId);
    Optional<TeamInvitation> findByTokenHash(String tokenHash);
    @Query("select i.teamId from TeamInvitation i where i.tokenHash = :tokenHash")
    Optional<UUID> findTeamIdByTokenHash(String tokenHash);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from TeamInvitation i where i.tokenHash = :tokenHash")
    Optional<TeamInvitation> lockByTokenHash(String tokenHash);
}
