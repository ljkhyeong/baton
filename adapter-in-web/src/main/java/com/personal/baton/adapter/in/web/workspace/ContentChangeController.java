package com.personal.baton.adapter.in.web.workspace;

import com.personal.baton.application.workspace.port.in.ContentChangeUseCase;
import com.personal.baton.domain.workspace.ContentRecordKind;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ContentChangeController {
    public static final String DECISION_PATH = "/api/v1/teams/{teamId}/seasons/{seasonId}/decisions/{recordId}/changes";
    public static final String RESOURCE_PATH = "/api/v1/teams/{teamId}/seasons/{seasonId}/role-resources/{recordId}/changes";
    private final ContentChangeUseCase useCase;
    public ContentChangeController(ContentChangeUseCase useCase) { this.useCase = useCase; }
    @GetMapping(DECISION_PATH)
    public ResponseEntity<ContentHistoryResponse> decisions(@PathVariable UUID teamId, @PathVariable UUID seasonId,
            @PathVariable UUID recordId, @RequestHeader(value = "X-Baton-Access-Key", required = false) String accessKey) {
        return history(teamId, seasonId, ContentRecordKind.DECISION, recordId, accessKey);
    }
    @GetMapping(RESOURCE_PATH)
    public ResponseEntity<ContentHistoryResponse> resources(@PathVariable UUID teamId, @PathVariable UUID seasonId,
            @PathVariable UUID recordId, @RequestHeader(value = "X-Baton-Access-Key", required = false) String accessKey) {
        return history(teamId, seasonId, ContentRecordKind.ROLE_RESOURCE, recordId, accessKey);
    }
    private ResponseEntity<ContentHistoryResponse> history(UUID teamId, UUID seasonId, ContentRecordKind kind, UUID recordId, String accessKey) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ContentHistoryResponse.from(useCase.getHistory(teamId, seasonId, kind, recordId, accessKey)));
    }
}
