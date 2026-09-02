package com.personal.baton.adapter.in.web.workspace;

import com.personal.baton.adapter.in.web.workspace.WorkspaceRequests.ArchiveRequest;
import com.personal.baton.adapter.in.web.workspace.WorkspaceRequests.CompletionRequest;
import com.personal.baton.adapter.in.web.workspace.WorkspaceRequests.CreateDecisionRequest;
import com.personal.baton.adapter.in.web.workspace.WorkspaceRequests.CreateHandoffItemRequest;
import com.personal.baton.adapter.in.web.workspace.WorkspaceRequests.CreateRoleResourceRequest;
import com.personal.baton.adapter.in.web.workspace.WorkspaceRequests.UpdateDecisionRequest;
import com.personal.baton.adapter.in.web.workspace.WorkspaceRequests.UpdateHandoffItemRequest;
import com.personal.baton.adapter.in.web.workspace.WorkspaceRequests.UpdateRoleResourceRequest;
import com.personal.baton.adapter.in.web.workspace.WorkspaceResponses.DecisionResponse;
import com.personal.baton.adapter.in.web.workspace.WorkspaceResponses.HandoffItemResponse;
import com.personal.baton.adapter.in.web.workspace.WorkspaceResponses.RoleResourceResponse;
import com.personal.baton.application.workspace.port.in.WorkspaceContract;
import com.personal.baton.application.workspace.port.in.WorkspaceRecordCommands;
import com.personal.baton.application.workspace.port.in.WorkspaceRecordsUseCase;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class WorkspaceRecordsController {

    private final WorkspaceRecordsUseCase recordsUseCase;

    public WorkspaceRecordsController(WorkspaceRecordsUseCase recordsUseCase) {
        this.recordsUseCase = recordsUseCase;
    }

    @PostMapping("/teams/{teamId}/seasons/{seasonId}/decisions")
    @ResponseStatus(HttpStatus.CREATED)
    public DecisionResponse createDecision(
            @PathVariable UUID teamId,
            @PathVariable UUID seasonId,
            @RequestHeader(
                    name = WorkspaceLifecycleController.IDEMPOTENCY_KEY_HEADER,
                    required = false
            ) String idempotencyKey,
            @RequestHeader(
                    name = WorkspaceLifecycleController.ACCESS_KEY_HEADER,
                    required = false
            ) String accessKey,
            @Valid @RequestBody CreateDecisionRequest request
    ) {
        WorkspaceContract.DecisionResult result = recordsUseCase.createDecision(
                teamId,
                seasonId,
                idempotencyKey,
                accessKey,
                new WorkspaceRecordCommands.CreateDecisionCommand(
                        request.title(),
                        request.reason(),
                        request.alternative(),
                        request.authorMemberId(),
                        request.roleIds()
                )
        );
        return DecisionResponse.from(result);
    }

    @PutMapping("/teams/{teamId}/seasons/{seasonId}/decisions/{decisionId}")
    public DecisionResponse updateDecision(
            @PathVariable UUID teamId,
            @PathVariable UUID seasonId,
            @PathVariable UUID decisionId,
            @RequestHeader(
                    name = WorkspaceLifecycleController.ACCESS_KEY_HEADER,
                    required = false
            ) String accessKey,
            @Valid @RequestBody UpdateDecisionRequest request
    ) {
        return DecisionResponse.from(recordsUseCase.updateDecision(
                teamId,
                seasonId,
                decisionId,
                accessKey,
                new WorkspaceRecordCommands.UpdateDecisionCommand(
                        request.title(),
                        request.reason(),
                        request.alternative(),
                        request.authorMemberId(),
                        request.roleIds()
                )
        ));
    }

    @PatchMapping("/teams/{teamId}/seasons/{seasonId}/decisions/{decisionId}/archive")
    public DecisionResponse updateDecisionArchive(
            @PathVariable UUID teamId,
            @PathVariable UUID seasonId,
            @PathVariable UUID decisionId,
            @RequestHeader(
                    name = WorkspaceLifecycleController.ACCESS_KEY_HEADER,
                    required = false
            ) String accessKey,
            @Valid @RequestBody ArchiveRequest request
    ) {
        return DecisionResponse.from(recordsUseCase.updateDecisionArchive(
                teamId,
                seasonId,
                decisionId,
                accessKey,
                request.archived()
        ));
    }

    @PostMapping("/teams/{teamId}/seasons/{seasonId}/handoff-items")
    @ResponseStatus(HttpStatus.CREATED)
    public HandoffItemResponse createHandoffItem(
            @PathVariable UUID teamId,
            @PathVariable UUID seasonId,
            @RequestHeader(
                    name = WorkspaceLifecycleController.IDEMPOTENCY_KEY_HEADER,
                    required = false
            ) String idempotencyKey,
            @RequestHeader(
                    name = WorkspaceLifecycleController.ACCESS_KEY_HEADER,
                    required = false
            ) String accessKey,
            @Valid @RequestBody CreateHandoffItemRequest request
    ) {
        WorkspaceContract.HandoffItemResult result = recordsUseCase.createHandoffItem(
                teamId,
                seasonId,
                idempotencyKey,
                accessKey,
                new WorkspaceRecordCommands.CreateHandoffItemCommand(
                        request.roleId(),
                        request.label(),
                        request.category()
                )
        );
        return HandoffItemResponse.from(result);
    }

    @PutMapping("/teams/{teamId}/seasons/{seasonId}/handoff-items/{itemId}")
    public HandoffItemResponse updateHandoffItem(
            @PathVariable UUID teamId,
            @PathVariable UUID seasonId,
            @PathVariable UUID itemId,
            @RequestHeader(
                    name = WorkspaceLifecycleController.ACCESS_KEY_HEADER,
                    required = false
            ) String accessKey,
            @Valid @RequestBody UpdateHandoffItemRequest request
    ) {
        return HandoffItemResponse.from(recordsUseCase.updateHandoffItem(
                teamId,
                seasonId,
                itemId,
                accessKey,
                new WorkspaceRecordCommands.UpdateHandoffItemCommand(
                        request.roleId(),
                        request.label(),
                        request.category()
                )
        ));
    }

    @PatchMapping("/teams/{teamId}/seasons/{seasonId}/handoff-items/{itemId}/completion")
    public HandoffItemResponse updateHandoffItemCompletion(
            @PathVariable UUID teamId,
            @PathVariable UUID seasonId,
            @PathVariable UUID itemId,
            @RequestHeader(
                    name = WorkspaceLifecycleController.ACCESS_KEY_HEADER,
                    required = false
            ) String accessKey,
            @Valid @RequestBody CompletionRequest request
    ) {
        return HandoffItemResponse.from(recordsUseCase.updateHandoffItemCompletion(
                teamId, seasonId, itemId, accessKey, request.completed()
        ));
    }

    @PatchMapping("/teams/{teamId}/seasons/{seasonId}/handoff-items/{itemId}/archive")
    public HandoffItemResponse updateHandoffItemArchive(
            @PathVariable UUID teamId,
            @PathVariable UUID seasonId,
            @PathVariable UUID itemId,
            @RequestHeader(
                    name = WorkspaceLifecycleController.ACCESS_KEY_HEADER,
                    required = false
            ) String accessKey,
            @Valid @RequestBody ArchiveRequest request
    ) {
        return HandoffItemResponse.from(recordsUseCase.updateHandoffItemArchive(
                teamId,
                seasonId,
                itemId,
                accessKey,
                request.archived()
        ));
    }

    @PostMapping("/teams/{teamId}/seasons/{seasonId}/role-resources")
    @ResponseStatus(HttpStatus.CREATED)
    public RoleResourceResponse createRoleResource(
            @PathVariable UUID teamId,
            @PathVariable UUID seasonId,
            @RequestHeader(
                    name = WorkspaceLifecycleController.IDEMPOTENCY_KEY_HEADER,
                    required = false
            ) String idempotencyKey,
            @RequestHeader(
                    name = WorkspaceLifecycleController.ACCESS_KEY_HEADER,
                    required = false
            ) String accessKey,
            @Valid @RequestBody CreateRoleResourceRequest request
    ) {
        WorkspaceContract.RoleResourceResult result = recordsUseCase.createRoleResource(
                teamId,
                seasonId,
                idempotencyKey,
                accessKey,
                new WorkspaceRecordCommands.CreateRoleResourceCommand(
                        request.roleId(),
                        request.title(),
                        request.url(),
                        request.description()
                )
        );
        return RoleResourceResponse.from(result);
    }

    @PutMapping("/teams/{teamId}/seasons/{seasonId}/role-resources/{resourceId}")
    public RoleResourceResponse updateRoleResource(
            @PathVariable UUID teamId,
            @PathVariable UUID seasonId,
            @PathVariable UUID resourceId,
            @RequestHeader(
                    name = WorkspaceLifecycleController.ACCESS_KEY_HEADER,
                    required = false
            ) String accessKey,
            @Valid @RequestBody UpdateRoleResourceRequest request
    ) {
        return RoleResourceResponse.from(recordsUseCase.updateRoleResource(
                teamId,
                seasonId,
                resourceId,
                accessKey,
                new WorkspaceRecordCommands.UpdateRoleResourceCommand(
                        request.roleId(),
                        request.title(),
                        request.url(),
                        request.description()
                )
        ));
    }

    @PatchMapping("/teams/{teamId}/seasons/{seasonId}/role-resources/{resourceId}/archive")
    public RoleResourceResponse updateRoleResourceArchive(
            @PathVariable UUID teamId,
            @PathVariable UUID seasonId,
            @PathVariable UUID resourceId,
            @RequestHeader(
                    name = WorkspaceLifecycleController.ACCESS_KEY_HEADER,
                    required = false
            ) String accessKey,
            @Valid @RequestBody ArchiveRequest request
    ) {
        return RoleResourceResponse.from(recordsUseCase.updateRoleResourceArchive(
                teamId,
                seasonId,
                resourceId,
                accessKey,
                request.archived()
        ));
    }
}
