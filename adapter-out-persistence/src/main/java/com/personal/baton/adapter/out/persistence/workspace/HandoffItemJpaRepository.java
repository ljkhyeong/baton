package com.personal.baton.adapter.out.persistence.workspace;

import com.personal.baton.domain.workspace.HandoffItem;
import com.personal.baton.application.workspace.port.out.WorkspaceRecordsRepository.HandoffItemCounts;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface HandoffItemJpaRepository extends JpaRepository<HandoffItem, UUID> {

    List<HandoffItem> findAllByRoleIdInOrderByIdAsc(List<UUID> roleIds);

    @Query("""
            select count(item) as activeCount,
                   coalesce(sum(case when item.completed = false then 1 else 0 end), 0) as incompleteCount
            from HandoffItem item
            where item.roleId = :roleId and item.archivedAt is null
            """)
    HandoffItemCounts countActiveByRoleId(UUID roleId);
}
