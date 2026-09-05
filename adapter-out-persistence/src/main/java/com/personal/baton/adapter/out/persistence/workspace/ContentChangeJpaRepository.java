package com.personal.baton.adapter.out.persistence.workspace;

import com.personal.baton.domain.workspace.ContentChange;
import com.personal.baton.domain.workspace.ContentRecordKind;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ContentChangeJpaRepository extends JpaRepository<ContentChange, UUID> {
    @Query("""
            select c.id from ContentChange c where c.teamId = :teamId and c.seasonId = :seasonId
              and c.recordKind = :kind and c.recordId = :recordId order by c.changedAt desc, c.id desc
            """)
    List<UUID> findRecentIds(UUID teamId, UUID seasonId, ContentRecordKind kind, UUID recordId, Pageable page);
    @Query("select c from ContentChange c left join fetch c.fields where c.id in :ids order by c.changedAt desc, c.id desc")
    List<ContentChange> findWithFields(List<UUID> ids);
}
