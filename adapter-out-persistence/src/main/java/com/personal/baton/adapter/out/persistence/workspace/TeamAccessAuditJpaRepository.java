package com.personal.baton.adapter.out.persistence.workspace;

import com.personal.baton.domain.workspace.TeamAccessAudit;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TeamAccessAuditJpaRepository extends JpaRepository<TeamAccessAudit, UUID> {
    List<TeamAccessAudit> findTop50ByTeamIdOrderByChangedAtDescIdDesc(UUID teamId);
}
