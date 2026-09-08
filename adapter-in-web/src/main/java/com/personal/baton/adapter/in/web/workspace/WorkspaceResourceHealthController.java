package com.personal.baton.adapter.in.web.workspace;

import com.personal.baton.application.workspace.port.in.InspectResourceHealthUseCase;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/teams/{teamId}/seasons/{seasonId}/role-resources/{resourceId}")
public class WorkspaceResourceHealthController {
    private final InspectResourceHealthUseCase useCase;

    public WorkspaceResourceHealthController(InspectResourceHealthUseCase useCase) { this.useCase = useCase; }

    @GetMapping("/health")
    public ResponseEntity<ResourceHealthResponse> inspect(
            @PathVariable UUID teamId, @PathVariable UUID seasonId, @PathVariable UUID resourceId,
            @RequestHeader(name = WorkspaceLifecycleController.ACCESS_KEY_HEADER, required = false) String accessKey) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ResourceHealthResponse.from(useCase.inspect(teamId, seasonId, resourceId, accessKey)));
    }

    @PostMapping("/check-requests")
    public ResponseEntity<ResourceCheckResponse> requestCheck(
            @PathVariable UUID teamId, @PathVariable UUID seasonId, @PathVariable UUID resourceId,
            @RequestHeader(name = WorkspaceLifecycleController.ACCESS_KEY_HEADER, required = false) String accessKey) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).cacheControl(CacheControl.noStore())
                .body(ResourceCheckResponse.from(useCase.requestCheck(teamId, seasonId, resourceId, accessKey)));
    }
}
