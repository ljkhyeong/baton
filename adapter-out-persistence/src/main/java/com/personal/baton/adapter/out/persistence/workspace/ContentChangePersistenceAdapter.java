package com.personal.baton.adapter.out.persistence.workspace;

import com.personal.baton.application.workspace.port.out.ContentChangeRepository;
import com.personal.baton.domain.workspace.ContentChange;
import com.personal.baton.domain.workspace.ContentRecordKind;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

@Repository
public class ContentChangePersistenceAdapter implements ContentChangeRepository {
    private final ContentChangeJpaRepository repository;
    public ContentChangePersistenceAdapter(ContentChangeJpaRepository repository) { this.repository = repository; }
    @Override public void save(ContentChange change) { repository.save(change); }
    @Override public List<ContentChange> findRecent(UUID teamId, UUID seasonId, ContentRecordKind kind, UUID recordId) {
        var ids = repository.findRecentIds(teamId, seasonId, kind, recordId, PageRequest.of(0, 50));
        return ids.isEmpty() ? List.of() : repository.findWithFields(ids);
    }
}
