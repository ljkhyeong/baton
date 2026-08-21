package com.personal.baton.adapter.out.persistence.workspace;

import com.personal.baton.domain.workspace.Member;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface MemberJpaRepository extends JpaRepository<Member, UUID> {

    List<Member> findAllByTeamIdOrderByNameAsc(UUID teamId);

    @Lock(LockModeType.PESSIMISTIC_READ)
    List<Member> findAllWithSharedLockByTeamIdAndIdInOrderByIdAsc(
            UUID teamId,
            List<UUID> memberIds
    );

}
