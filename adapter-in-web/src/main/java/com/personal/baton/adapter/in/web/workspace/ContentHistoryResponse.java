package com.personal.baton.adapter.in.web.workspace;

import com.personal.baton.application.workspace.port.in.ContentChangeUseCase.ContentHistoryResult;
import com.personal.baton.domain.workspace.ContentRecordKind;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ContentHistoryResponse(UUID teamId, UUID seasonId, ContentRecordKind recordKind, UUID recordId, List<ContentChangeResponse> changes) {
    public record FieldChangeResponse(String fieldName, String beforeValue, String afterValue) {}
    public record ContentChangeResponse(UUID id, UUID actorAccountId, String actorName, Instant changedAt, List<FieldChangeResponse> fields) {}
    static ContentHistoryResponse from(ContentHistoryResult value) {
        return new ContentHistoryResponse(value.teamId(), value.seasonId(), value.recordKind(), value.recordId(), value.changes().stream()
                .map(change -> new ContentChangeResponse(change.id(), change.actorAccountId(), change.actorName(), change.changedAt(),
                        change.fields().stream().map(field -> new FieldChangeResponse(field.fieldName(), field.beforeValue(), field.afterValue())).toList())).toList());
    }
}
