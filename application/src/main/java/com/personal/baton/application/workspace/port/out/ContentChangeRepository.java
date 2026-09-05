package com.personal.baton.application.workspace.port.out;

import com.personal.baton.domain.workspace.ContentChange;
import com.personal.baton.domain.workspace.ContentRecordKind;
import java.util.List;
import java.util.UUID;

public interface ContentChangeRepository {
    void save(ContentChange change);
    List<ContentChange> findRecent(UUID teamId, UUID seasonId, ContentRecordKind kind, UUID recordId);
}
