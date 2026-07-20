package com.personal.baton.adapter.out.persistence.workspace;

import com.personal.baton.domain.workspace.Member;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberJpaRepository extends JpaRepository<Member, UUID> {

    List<Member> findAllByTeamIdOrderByNameAsc(UUID teamId);
}
