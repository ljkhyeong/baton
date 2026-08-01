package com.personal.baton.adapter.in.web.workspace;

import com.personal.baton.adapter.in.web.workspace.WorkspaceRequests.ConfirmRoleHandoffRequest;
import com.personal.baton.adapter.in.web.workspace.WorkspaceRequests.CreateRoleRequest;
import com.personal.baton.adapter.in.web.workspace.WorkspaceRequests.PrepareRoleHandoffRequest;
import com.personal.baton.adapter.in.web.workspace.WorkspaceRequests.TransferRoleHandoffRequest;
import com.personal.baton.adapter.in.web.workspace.WorkspaceRequests.UpdateRoleRequest;
import com.personal.baton.adapter.in.web.workspace.WorkspaceResponses.RoleHandoffTransitionResponse;
import com.personal.baton.adapter.in.web.workspace.WorkspaceResponses.RoleResponse;
import com.personal.baton.application.workspace.port.in.WorkspaceRoleHandoffUseCase;
import com.personal.baton.application.workspace.port.in.WorkspaceRoleUseCase;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.personal.baton.adapter.in.web.workspace.WorkspaceControllerSupport.ACCESS_KEY_HEADER;
import static com.personal.baton.adapter.in.web.workspace.WorkspaceControllerSupport.IDEMPOTENCY_KEY_HEADER;
import static com.personal.baton.adapter.in.web.workspace.WorkspaceControllerSupport.invokeAuthorized;

@RestController
@RequestMapping("/api/v1")
public class WorkspaceRoleController {

    private final WorkspaceRoleUseCase roleUseCase;
    private final WorkspaceRoleHandoffUseCase roleHandoffUseCase;

    public WorkspaceRoleController(
            WorkspaceRoleUseCase roleUseCase,
            WorkspaceRoleHandoffUseCase roleHandoffUseCase
    ) {
        this.roleUseCase = roleUseCase;
        this.roleHandoffUseCase = roleHandoffUseCase;
    }

    @PostMapping("/teams/{teamId}/seasons/{seasonId}/roles")
    public ResponseEntity<RoleResponse> createRole(
            @PathVariable UUID teamId,
            @PathVariable UUID seasonId,
            @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestHeader(name = ACCESS_KEY_HEADER, required = false) String accessKey,
            Authentication principal,
            @Valid @RequestBody CreateRoleRequest request
    ) {
        var command = new WorkspaceRoleUseCase.CreateRoleCommand(
                request.name(),
                request.purpose(),
                request.currentMemberId(),
                request.nextMemberId(),
                request.assignmentStartDate(),
                request.assignmentEndDate(),
                request.responsibilities(),
                request.risk()
        );
        WorkspaceRoleUseCase.RoleResult result = invokeAuthorized(
                accessKey,
                principal,
                key -> roleUseCase.createRole(
                        teamId,
                        seasonId,
                        idempotencyKey,
                        key,
                        command
                ),
                authorization -> roleUseCase.createRoleAuthorized(
                        teamId,
                        seasonId,
                        idempotencyKey,
                        authorization,
                        command
                )
        );
        return ResponseEntity.status(201).body(RoleResponse.from(result));
    }

    @PutMapping("/teams/{teamId}/seasons/{seasonId}/roles/{roleId}")
    public RoleResponse updateRole(
            @PathVariable UUID teamId,
            @PathVariable UUID seasonId,
            @PathVariable UUID roleId,
            @RequestHeader(name = ACCESS_KEY_HEADER, required = false) String accessKey,
            Authentication principal,
            @Valid @RequestBody UpdateRoleRequest request
    ) {
        var command = new WorkspaceRoleUseCase.UpdateRoleCommand(
                request.name(),
                request.purpose(),
                request.currentMemberId(),
                request.nextMemberId(),
                request.assignmentStartDate(),
                request.assignmentEndDate(),
                request.responsibilities(),
                request.risk()
        );
        return RoleResponse.from(invokeAuthorized(
                accessKey,
                principal,
                key -> roleUseCase.updateRole(
                        teamId,
                        seasonId,
                        roleId,
                        key,
                        command
                ),
                authorization -> roleUseCase.updateRoleAuthorized(
                        teamId,
                        seasonId,
                        roleId,
                        authorization,
                        command
                )
        ));
    }

    @PostMapping("/teams/{teamId}/seasons/{seasonId}/roles/{roleId}/handoffs")
    public ResponseEntity<RoleHandoffTransitionResponse> prepareRoleHandoff(
            @PathVariable UUID teamId,
            @PathVariable UUID seasonId,
            @PathVariable UUID roleId,
            @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestHeader(name = ACCESS_KEY_HEADER, required = false) String accessKey,
            Authentication principal,
            @Valid @RequestBody PrepareRoleHandoffRequest request
    ) {
        var command = new WorkspaceRoleHandoffUseCase.PrepareRoleHandoffCommand(
                request.toMemberId(),
                request.incomingAssignmentStartDate(),
                request.incomingAssignmentEndDate()
        );
        WorkspaceRoleHandoffUseCase.RoleHandoffTransitionResult result = invokeAuthorized(
                accessKey,
                principal,
                key -> roleHandoffUseCase.prepareRoleHandoff(
                        teamId,
                        seasonId,
                        roleId,
                        idempotencyKey,
                        key,
                        command
                ),
                authorization -> roleHandoffUseCase.prepareRoleHandoffAuthorized(
                        teamId,
                        seasonId,
                        roleId,
                        idempotencyKey,
                        authorization,
                        command
                )
        );
        URI location = URI.create("/api/v1/teams/" + teamId
                + "/seasons/" + seasonId
                + "/roles/" + roleId
                + "/handoffs/" + result.handoff().id());
        return ResponseEntity.created(location)
                .body(RoleHandoffTransitionResponse.from(result));
    }

    @PatchMapping(
            "/teams/{teamId}/seasons/{seasonId}/roles/{roleId}"
                    + "/handoffs/{handoffId}/transfer"
    )
    public RoleHandoffTransitionResponse transferRoleHandoff(
            @PathVariable UUID teamId,
            @PathVariable UUID seasonId,
            @PathVariable UUID roleId,
            @PathVariable UUID handoffId,
            @RequestHeader(name = ACCESS_KEY_HEADER, required = false) String accessKey,
            Authentication principal,
            @Valid @RequestBody TransferRoleHandoffRequest request
    ) {
        var command = new WorkspaceRoleHandoffUseCase.TransferRoleHandoffCommand(
                request.confirmedByMemberId(),
                request.warningAcknowledged()
        );
        return RoleHandoffTransitionResponse.from(invokeAuthorized(
                accessKey,
                principal,
                key -> roleHandoffUseCase.transferRoleHandoff(
                        teamId,
                        seasonId,
                        roleId,
                        handoffId,
                        key,
                        command
                ),
                authorization -> roleHandoffUseCase.transferRoleHandoffAuthorized(
                        teamId,
                        seasonId,
                        roleId,
                        handoffId,
                        authorization,
                        command
                )
        ));
    }

    @PatchMapping(
            "/teams/{teamId}/seasons/{seasonId}/roles/{roleId}"
                    + "/handoffs/{handoffId}/acceptance"
    )
    public RoleHandoffTransitionResponse acceptRoleHandoff(
            @PathVariable UUID teamId,
            @PathVariable UUID seasonId,
            @PathVariable UUID roleId,
            @PathVariable UUID handoffId,
            @RequestHeader(name = ACCESS_KEY_HEADER, required = false) String accessKey,
            Authentication principal,
            @Valid @RequestBody ConfirmRoleHandoffRequest request
    ) {
        var command = new WorkspaceRoleHandoffUseCase.ConfirmRoleHandoffCommand(
                request.confirmedByMemberId()
        );
        return RoleHandoffTransitionResponse.from(invokeAuthorized(
                accessKey,
                principal,
                key -> roleHandoffUseCase.acceptRoleHandoff(
                        teamId,
                        seasonId,
                        roleId,
                        handoffId,
                        key,
                        command
                ),
                authorization -> roleHandoffUseCase.acceptRoleHandoffAuthorized(
                        teamId,
                        seasonId,
                        roleId,
                        handoffId,
                        authorization,
                        command
                )
        ));
    }

    @PatchMapping(
            "/teams/{teamId}/seasons/{seasonId}/roles/{roleId}"
                    + "/handoffs/{handoffId}/cancellation"
    )
    public RoleHandoffTransitionResponse cancelRoleHandoff(
            @PathVariable UUID teamId,
            @PathVariable UUID seasonId,
            @PathVariable UUID roleId,
            @PathVariable UUID handoffId,
            @RequestHeader(name = ACCESS_KEY_HEADER, required = false) String accessKey,
            Authentication principal,
            @Valid @RequestBody ConfirmRoleHandoffRequest request
    ) {
        var command = new WorkspaceRoleHandoffUseCase.ConfirmRoleHandoffCommand(
                request.confirmedByMemberId()
        );
        return RoleHandoffTransitionResponse.from(invokeAuthorized(
                accessKey,
                principal,
                key -> roleHandoffUseCase.cancelRoleHandoff(
                        teamId,
                        seasonId,
                        roleId,
                        handoffId,
                        key,
                        command
                ),
                authorization -> roleHandoffUseCase.cancelRoleHandoffAuthorized(
                        teamId,
                        seasonId,
                        roleId,
                        handoffId,
                        authorization,
                        command
                )
        ));
    }
}
