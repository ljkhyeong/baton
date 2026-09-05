package com.personal.baton.application.workspace;

import com.personal.baton.application.workspace.error.WorkspaceNotFoundException;
import com.personal.baton.application.workspace.port.in.ContentChangeUseCase;
import com.personal.baton.application.workspace.port.out.ContentChangeRepository;
import com.personal.baton.application.workspace.port.out.WorkspaceRecordsRepository;
import com.personal.baton.domain.workspace.ContentRecordKind;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ContentChangeService implements ContentChangeUseCase {
    private final WorkspaceScopeAuthorizer authorizer;
    private final WorkspaceRoleResolver roles;
    private final WorkspaceRecordsRepository records;
    private final ContentChangeRepository changes;
    public ContentChangeService(WorkspaceScopeAuthorizer authorizer, WorkspaceRoleResolver roles,
            WorkspaceRecordsRepository records, ContentChangeRepository changes) {
        this.authorizer = authorizer; this.roles = roles; this.records = records; this.changes = changes;
    }
    @Override public ContentHistoryResult getHistory(UUID teamId, UUID seasonId, ContentRecordKind kind, UUID recordId, String accessKey) {
        authorizer.authorizeRead(teamId, seasonId, accessKey);
        if (kind == ContentRecordKind.DECISION) {
            records.findDecisionById(recordId).filter(value -> value.getSeasonId().equals(seasonId))
                    .orElseThrow(() -> new WorkspaceNotFoundException("DECISION_NOT_FOUND", "결정 기록을 찾을 수 없습니다"));
        } else {
            var resource = records.findRoleResourceById(recordId)
                    .orElseThrow(() -> new WorkspaceNotFoundException("ROLE_RESOURCE_NOT_FOUND", "자료를 찾을 수 없습니다"));
            roles.requireRole(teamId, seasonId, resource.getRoleId());
        }
        return new ContentHistoryResult(teamId, seasonId, kind, recordId, changes.findRecent(teamId, seasonId, kind, recordId)
                .stream().map(value -> new ContentChangeResult(value.getId(), value.getActorAccountId(), value.getActorName(),
                        value.getChangedAt(), value.getFields().stream().map(field -> new FieldChangeResult(field.getFieldName(),
                        field.getBeforeValue(), field.getAfterValue())).toList())).toList());
    }
}
