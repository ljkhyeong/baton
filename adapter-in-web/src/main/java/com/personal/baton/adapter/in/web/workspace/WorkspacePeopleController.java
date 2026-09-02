package com.personal.baton.adapter.in.web.workspace;

import com.personal.baton.adapter.in.web.workspace.WorkspaceRequests.ConfirmRoleHandoffRequest;
import com.personal.baton.adapter.in.web.workspace.WorkspaceRequests.CreateMemberRequest;
import com.personal.baton.adapter.in.web.workspace.WorkspaceRequests.CreateRoleRequest;
import com.personal.baton.adapter.in.web.workspace.WorkspaceRequests.MemberDeactivationRequest;
import com.personal.baton.adapter.in.web.workspace.WorkspaceRequests.PrepareRoleHandoffRequest;
import com.personal.baton.adapter.in.web.workspace.WorkspaceRequests.TransferRoleHandoffRequest;
import com.personal.baton.adapter.in.web.workspace.WorkspaceRequests.UpdateMemberRequest;
import com.personal.baton.adapter.in.web.workspace.WorkspaceRequests.UpdateRoleRequest;
import com.personal.baton.adapter.in.web.workspace.WorkspaceResponses.MemberResponse;
import com.personal.baton.adapter.in.web.workspace.WorkspaceResponses.RoleHandoffTransitionResponse;
import com.personal.baton.adapter.in.web.workspace.WorkspaceResponses.RoleResponse;
import com.personal.baton.application.workspace.port.in.WorkspaceContract;
import com.personal.baton.application.workspace.port.in.WorkspacePeopleUseCase;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

@RestController
@RequestMapping("/api/v1")
public class WorkspacePeopleController {

    private final WorkspacePeopleUseCase peopleUseCase;

    public WorkspacePeopleController(WorkspacePeopleUseCase peopleUseCase) {
        this.peopleUseCase = peopleUseCase;
    }

    @PostMapping("/teams/{teamId}/seasons/{seasonId}/members")
    @ResponseStatus(HttpStatus.CREATED)
    public MemberResponse createMember(
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
            @Valid @RequestBody CreateMemberRequest request
    ) {
        WorkspaceContract.MemberResult result = peopleUseCase.createMember(
                teamId,
                seasonId,
                idempotencyKey,
                accessKey,
                new WorkspaceContract.CreateMemberCommand(request.name())
        );
        return MemberResponse.from(result);
    }

    @PutMapping("/teams/{teamId}/seasons/{seasonId}/members/{memberId}")
    public MemberResponse updateMember(
            @PathVariable UUID teamId,
            @PathVariable UUID seasonId,
            @PathVariable UUID memberId,
            @RequestHeader(
                    name = WorkspaceLifecycleController.ACCESS_KEY_HEADER,
                    required = false
            ) String accessKey,
            @Valid @RequestBody UpdateMemberRequest request
    ) {
        return MemberResponse.from(peopleUseCase.updateMember(
                teamId,
                seasonId,
                memberId,
                accessKey,
                new WorkspaceContract.UpdateMemberCommand(request.name())
        ));
    }

    @PatchMapping("/teams/{teamId}/seasons/{seasonId}/members/{memberId}/deactivation")
    public MemberResponse updateMemberDeactivation(
            @PathVariable UUID teamId,
            @PathVariable UUID seasonId,
            @PathVariable UUID memberId,
            @RequestHeader(
                    name = WorkspaceLifecycleController.ACCESS_KEY_HEADER,
                    required = false
            ) String accessKey,
            @Valid @RequestBody MemberDeactivationRequest request
    ) {
        return MemberResponse.from(peopleUseCase.updateMemberDeactivation(
                teamId,
                seasonId,
                memberId,
                accessKey,
                request.deactivated()
        ));
    }

    @PostMapping("/teams/{teamId}/seasons/{seasonId}/roles")
    @ResponseStatus(HttpStatus.CREATED)
    public RoleResponse createRole(
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
            @Valid @RequestBody CreateRoleRequest request
    ) {
        WorkspaceContract.RoleResult result = peopleUseCase.createRole(
                teamId,
                seasonId,
                idempotencyKey,
                accessKey,
                new WorkspaceContract.CreateRoleCommand(
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
        return RoleResponse.from(result);
    }

    @PutMapping("/teams/{teamId}/seasons/{seasonId}/roles/{roleId}")
    public RoleResponse updateRole(
            @PathVariable UUID teamId,
            @PathVariable UUID seasonId,
            @PathVariable UUID roleId,
            @RequestHeader(
                    name = WorkspaceLifecycleController.ACCESS_KEY_HEADER,
                    required = false
            ) String accessKey,
            @Valid @RequestBody UpdateRoleRequest request
    ) {
        return RoleResponse.from(peopleUseCase.updateRole(
                teamId,
                seasonId,
                roleId,
                accessKey,
                new WorkspaceContract.UpdateRoleCommand(
                        request.name(),
                        request.purpose(),
                        request.currentMemberId(),
                        request.nextMemberId(),
                        request.assignmentStartDate(),
                        request.assignmentEndDate(),
                        request.responsibilities(),
                        request.risk()
                )
        ));
    }

    @PostMapping("/teams/{teamId}/seasons/{seasonId}/roles/{roleId}/handoffs")
    public ResponseEntity<RoleHandoffTransitionResponse> prepareRoleHandoff(
            @PathVariable UUID teamId,
            @PathVariable UUID seasonId,
            @PathVariable UUID roleId,
            @RequestHeader(
                    name = WorkspaceLifecycleController.IDEMPOTENCY_KEY_HEADER,
                    required = false
            ) String idempotencyKey,
            @RequestHeader(
                    name = WorkspaceLifecycleController.ACCESS_KEY_HEADER,
                    required = false
            ) String accessKey,
            @Valid @RequestBody PrepareRoleHandoffRequest request
    ) {
        WorkspaceContract.RoleHandoffTransitionResult result = peopleUseCase.prepareRoleHandoff(
                teamId,
                seasonId,
                roleId,
                idempotencyKey,
                accessKey,
                new WorkspaceContract.PrepareRoleHandoffCommand(
                        request.toMemberId(),
                        request.incomingAssignmentStartDate(),
                        request.incomingAssignmentEndDate()
                )
        );
        URI location = roleHandoffLocation(teamId, seasonId, roleId, result.handoff().id());
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
            @RequestHeader(
                    name = WorkspaceLifecycleController.ACCESS_KEY_HEADER,
                    required = false
            ) String accessKey,
            @Valid @RequestBody TransferRoleHandoffRequest request
    ) {
        return RoleHandoffTransitionResponse.from(peopleUseCase.transferRoleHandoff(
                teamId,
                seasonId,
                roleId,
                handoffId,
                accessKey,
                new WorkspaceContract.TransferRoleHandoffCommand(
                        request.confirmedByMemberId(),
                        request.warningAcknowledged()
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
            @RequestHeader(
                    name = WorkspaceLifecycleController.ACCESS_KEY_HEADER,
                    required = false
            ) String accessKey,
            @Valid @RequestBody ConfirmRoleHandoffRequest request
    ) {
        return RoleHandoffTransitionResponse.from(peopleUseCase.acceptRoleHandoff(
                teamId,
                seasonId,
                roleId,
                handoffId,
                accessKey,
                new WorkspaceContract.ConfirmRoleHandoffCommand(request.confirmedByMemberId())
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
            @RequestHeader(
                    name = WorkspaceLifecycleController.ACCESS_KEY_HEADER,
                    required = false
            ) String accessKey,
            @Valid @RequestBody ConfirmRoleHandoffRequest request
    ) {
        return RoleHandoffTransitionResponse.from(peopleUseCase.cancelRoleHandoff(
                teamId,
                seasonId,
                roleId,
                handoffId,
                accessKey,
                new WorkspaceContract.ConfirmRoleHandoffCommand(request.confirmedByMemberId())
        ));
    }

    private URI roleHandoffLocation(UUID teamId, UUID seasonId, UUID roleId, UUID handoffId) {
        return UriComponentsBuilder
                .fromPath(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}"
                                + "/roles/{roleId}/handoffs/{handoffId}"
                )
                .build(teamId, seasonId, roleId, handoffId);
    }
}
