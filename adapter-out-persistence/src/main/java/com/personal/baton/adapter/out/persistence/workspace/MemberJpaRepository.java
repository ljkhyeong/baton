package com.personal.baton.adapter.out.persistence.workspace;

import com.personal.baton.domain.workspace.Member;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MemberJpaRepository extends JpaRepository<Member, UUID> {

    List<Member> findAllByTeamIdOrderByNameAsc(UUID teamId);

    Optional<Member> findByTeamIdAndId(UUID teamId, UUID memberId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select member
            from Member member
            where member.teamId = :teamId
              and member.id = :memberId
            """)
    Optional<Member> findByTeamIdAndIdForUpdate(
            @Param("teamId") UUID teamId,
            @Param("memberId") UUID memberId
    );

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("""
            select member
            from Member member
            where member.teamId = :teamId
              and member.id in :memberIds
            order by member.id
            """)
    List<Member> findAllByTeamIdAndIdInWithSharedLock(
            @Param("teamId") UUID teamId,
            @Param("memberIds") List<UUID> memberIds
    );

    boolean existsByTeamIdAndName(UUID teamId, String name);

    boolean existsByTeamIdAndNameAndIdNot(UUID teamId, String name, UUID memberId);
}
