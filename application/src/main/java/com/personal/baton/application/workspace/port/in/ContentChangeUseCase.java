package com.personal.baton.application.workspace.port.in;

import com.personal.baton.domain.workspace.ContentRecordKind;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ContentChangeUseCase {
    ContentHistoryResult getHistory(UUID teamId, UUID seasonId, ContentRecordKind kind, UUID recordId, String accessKey);
    record FieldChangeResult(String fieldName, String beforeValue, String afterValue) {}
    record ContentChangeResult(UUID id, UUID actorAccountId, String actorName, Instant changedAt, List<FieldChangeResult> fields) {}
    record ContentHistoryResult(UUID teamId, UUID seasonId, ContentRecordKind recordKind, UUID recordId, List<ContentChangeResult> changes) {}
}
