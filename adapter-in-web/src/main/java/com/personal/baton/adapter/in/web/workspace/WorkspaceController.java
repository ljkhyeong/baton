package com.personal.baton.adapter.in.web.workspace;

import com.personal.baton.adapter.in.web.workspace.WorkspaceRequests.CompletionRequest;
import com.personal.baton.adapter.in.web.workspace.WorkspaceRequests.CreateDecisionRequest;
import com.personal.baton.adapter.in.web.workspace.WorkspaceRequests.CreateHandoffItemRequest;
import com.personal.baton.adapter.in.web.workspace.WorkspaceRequests.CreateRoleRequest;
import com.personal.baton.adapter.in.web.workspace.WorkspaceRequests.CreateRoutineRequest;
import com.personal.baton.adapter.in.web.workspace.WorkspaceRequests.CreateWorkspaceRequest;
import com.personal.baton.adapter.in.web.workspace.WorkspaceResponses.AccessKeyResponse;
import com.personal.baton.adapter.in.web.workspace.WorkspaceResponses.CreateWorkspaceResponse;
import com.personal.baton.adapter.in.web.workspace.WorkspaceResponses.DecisionResponse;
import com.personal.baton.adapter.in.web.workspace.WorkspaceResponses.HandoffItemResponse;
import com.personal.baton.adapter.in.web.workspace.WorkspaceResponses.RoleResponse;
import com.personal.baton.adapter.in.web.workspace.WorkspaceResponses.RoutineResponse;
import com.personal.baton.adapter.in.web.workspace.WorkspaceResponses.WorkspaceResponse;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class WorkspaceController {

    static final String ACCESS_KEY_HEADER = "X-Baton-Access-Key";
    static final String CREATION_KEY_HEADER = "X-Baton-Creation-Key";
    static final String RECOVERY_KEY_HEADER = "X-Baton-Recovery-Key";
    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final WorkspaceUseCase workspaceUseCase;

    public WorkspaceController(WorkspaceUseCase workspaceUseCase) {
        this.workspaceUseCase = workspaceUseCase;
    }

    @PostMapping("/workspaces")
    public ResponseEntity<CreateWorkspaceResponse> createWorkspace(
            @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestHeader(name = CREATION_KEY_HEADER, required = false) String creationKey,
            @Valid @RequestBody CreateWorkspaceRequest request
    ) {
        WorkspaceUseCase.CreatedWorkspaceResult result = workspaceUseCase.createWorkspace(
                idempotencyKey,
                creationKey,
                new WorkspaceUseCase.CreateWorkspaceCommand(
                        request.teamName(),
                        request.seasonName(),
                        request.startDate(),
                        request.endDate(),
                        request.memberNames()
                )
        );
        URI location = URI.create("/api/v1/teams/" + result.teamId()
                + "/seasons/" + result.seasonId() + "/workspace");
        return ResponseEntity.created(location)
                .cacheControl(CacheControl.noStore())
                .body(CreateWorkspaceResponse.from(result));
    }

    @GetMapping("/teams/{teamId}/seasons/{seasonId}/workspace")
    public ResponseEntity<WorkspaceResponse> getWorkspace(
            @PathVariable UUID teamId,
            @PathVariable UUID seasonId,
            @RequestHeader(name = ACCESS_KEY_HEADER, required = false) String accessKey
    ) {
        WorkspaceResponse response = WorkspaceResponse.from(
                workspaceUseCase.getWorkspace(teamId, seasonId, accessKey)
        );
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    @PostMapping("/teams/{teamId}/seasons/{seasonId}/access-key/rotate")
    public ResponseEntity<AccessKeyResponse> rotateAccessKey(
            @PathVariable UUID teamId,
            @PathVariable UUID seasonId,
            @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestHeader(name = ACCESS_KEY_HEADER, required = false) String accessKey
    ) {
        WorkspaceUseCase.AccessKeyResult result = workspaceUseCase.rotateAccessKey(
                teamId,
                seasonId,
                idempotencyKey,
                accessKey
        );
        return noStoreAccessKey(result);
    }

    @PostMapping("/teams/{teamId}/seasons/{seasonId}/access-key/recover")
    public ResponseEntity<AccessKeyResponse> recoverAccessKey(
            @PathVariable UUID teamId,
            @PathVariable UUID seasonId,
            @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestHeader(name = RECOVERY_KEY_HEADER, required = false) String recoveryKey
    ) {
        WorkspaceUseCase.AccessKeyResult result = workspaceUseCase.recoverAccessKey(
                teamId,
                seasonId,
                idempotencyKey,
                recoveryKey
        );
        return noStoreAccessKey(result);
    }

    @PostMapping("/teams/{teamId}/seasons/{seasonId}/roles")
    public ResponseEntity<RoleResponse> createRole(
            @PathVariable UUID teamId,
            @PathVariable UUID seasonId,
            @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestHeader(name = ACCESS_KEY_HEADER, required = false) String accessKey,
            @Valid @RequestBody CreateRoleRequest request
    ) {
        WorkspaceUseCase.RoleResult result = workspaceUseCase.createRole(
                teamId,
                seasonId,
                idempotencyKey,
                accessKey,
                new WorkspaceUseCase.CreateRoleCommand(
                        request.name(),
                        request.purpose(),
                        request.currentMemberId(),
                        request.nextMemberId(),
                        request.assignmentStartDate(),
                        request.assignmentEndDate(),
                        request.responsibilities(),
                        request.risk()
                )
        );
        return ResponseEntity.status(201).body(RoleResponse.from(result));
    }

    @PostMapping("/teams/{teamId}/seasons/{seasonId}/routines")
    public ResponseEntity<RoutineResponse> createRoutine(
            @PathVariable UUID teamId,
            @PathVariable UUID seasonId,
            @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestHeader(name = ACCESS_KEY_HEADER, required = false) String accessKey,
            @Valid @RequestBody CreateRoutineRequest request
    ) {
        WorkspaceUseCase.RoutineResult result = workspaceUseCase.createRoutine(
                teamId,
                seasonId,
                idempotencyKey,
                accessKey,
                new WorkspaceUseCase.CreateRoutineCommand(
                        request.title(),
                        request.phase(),
                        request.dueLabel(),
                        request.ownerRoleId(),
                        request.detail()
                )
        );
        return ResponseEntity.status(201).body(RoutineResponse.from(result));
    }

    @PatchMapping("/teams/{teamId}/seasons/{seasonId}/routines/{routineId}/completion")
    public RoutineResponse updateRoutineCompletion(
            @PathVariable UUID teamId,
            @PathVariable UUID seasonId,
            @PathVariable UUID routineId,
            @RequestHeader(name = ACCESS_KEY_HEADER, required = false) String accessKey,
            @Valid @RequestBody CompletionRequest request
    ) {
        return RoutineResponse.from(workspaceUseCase.updateRoutineCompletion(
                teamId, seasonId, routineId, accessKey, request.completed()));
    }

    @PostMapping("/teams/{teamId}/seasons/{seasonId}/decisions")
    public ResponseEntity<DecisionResponse> createDecision(
            @PathVariable UUID teamId,
            @PathVariable UUID seasonId,
            @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestHeader(name = ACCESS_KEY_HEADER, required = false) String accessKey,
            @Valid @RequestBody CreateDecisionRequest request
    ) {
        WorkspaceUseCase.DecisionResult result = workspaceUseCase.createDecision(
                teamId,
                seasonId,
                idempotencyKey,
                accessKey,
                new WorkspaceUseCase.CreateDecisionCommand(
                        request.title(),
                        request.reason(),
                        request.alternative(),
                        request.authorMemberId(),
                        request.roleIds()
                )
        );
        return ResponseEntity.status(201).body(DecisionResponse.from(result));
    }

    @PostMapping("/teams/{teamId}/seasons/{seasonId}/handoff-items")
    public ResponseEntity<HandoffItemResponse> createHandoffItem(
            @PathVariable UUID teamId,
            @PathVariable UUID seasonId,
            @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestHeader(name = ACCESS_KEY_HEADER, required = false) String accessKey,
            @Valid @RequestBody CreateHandoffItemRequest request
    ) {
        WorkspaceUseCase.HandoffItemResult result = workspaceUseCase.createHandoffItem(
                teamId,
                seasonId,
                idempotencyKey,
                accessKey,
                new WorkspaceUseCase.CreateHandoffItemCommand(
                        request.roleId(),
                        request.label(),
                        request.category()
                )
        );
        return ResponseEntity.status(201).body(HandoffItemResponse.from(result));
    }

    @PatchMapping("/teams/{teamId}/seasons/{seasonId}/handoff-items/{itemId}/completion")
    public HandoffItemResponse updateHandoffItemCompletion(
            @PathVariable UUID teamId,
            @PathVariable UUID seasonId,
            @PathVariable UUID itemId,
            @RequestHeader(name = ACCESS_KEY_HEADER, required = false) String accessKey,
            @Valid @RequestBody CompletionRequest request
    ) {
        return HandoffItemResponse.from(workspaceUseCase.updateHandoffItemCompletion(
                teamId, seasonId, itemId, accessKey, request.completed()));
    }

    private ResponseEntity<AccessKeyResponse> noStoreAccessKey(WorkspaceUseCase.AccessKeyResult result) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(AccessKeyResponse.from(result));
    }
}
