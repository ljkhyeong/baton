package com.personal.baton.adapter.in.web.workspace;

import com.personal.baton.adapter.in.web.workspace.WorkspaceRequests.CompletionRequest;
import com.personal.baton.adapter.in.web.workspace.WorkspaceRequests.ArchiveRequest;
import com.personal.baton.adapter.in.web.workspace.WorkspaceRequests.ConfirmRoleHandoffRequest;
import com.personal.baton.adapter.in.web.workspace.WorkspaceRequests.CreateDecisionRequest;
import com.personal.baton.adapter.in.web.workspace.WorkspaceRequests.CreateHandoffItemRequest;
import com.personal.baton.adapter.in.web.workspace.WorkspaceRequests.CreateMemberRequest;
import com.personal.baton.adapter.in.web.workspace.WorkspaceRequests.CreateOwnedWorkspaceRequest;
import com.personal.baton.adapter.in.web.workspace.WorkspaceRequests.CreateRoleRequest;
import com.personal.baton.adapter.in.web.workspace.WorkspaceRequests.CreateRoleResourceRequest;
import com.personal.baton.adapter.in.web.workspace.WorkspaceRequests.CreateRoutineRequest;
import com.personal.baton.adapter.in.web.workspace.WorkspaceRequests.CreateSeasonRoundRequest;
import com.personal.baton.adapter.in.web.workspace.WorkspaceRequests.CreateWorkspaceRequest;
import com.personal.baton.adapter.in.web.workspace.WorkspaceRequests.MemberDeactivationRequest;
import com.personal.baton.adapter.in.web.workspace.WorkspaceRequests.PrepareRoleHandoffRequest;
import com.personal.baton.adapter.in.web.workspace.WorkspaceRequests.TransferRoleHandoffRequest;
import com.personal.baton.adapter.in.web.workspace.WorkspaceRequests.UpdateMemberRequest;
import com.personal.baton.adapter.in.web.workspace.WorkspaceRequests.UpdateRoleRequest;
import com.personal.baton.adapter.in.web.workspace.WorkspaceRequests.UpdateRoleResourceRequest;
import com.personal.baton.adapter.in.web.workspace.WorkspaceRequests.UpdateRoutineExecutionCompletionRequest;
import com.personal.baton.adapter.in.web.workspace.WorkspaceRequests.UpdateRoutineRequest;
import com.personal.baton.adapter.in.web.workspace.WorkspaceRequests.UpdateSeasonRoundRequest;
import com.personal.baton.adapter.in.web.workspace.WorkspaceRequests.UpdateDecisionRequest;
import com.personal.baton.adapter.in.web.workspace.WorkspaceRequests.UpdateHandoffItemRequest;
import com.personal.baton.adapter.in.web.workspace.WorkspaceResponses.AccessKeyResponse;
import com.personal.baton.adapter.in.web.workspace.WorkspaceResponses.CreateOwnedWorkspaceResponse;
import com.personal.baton.adapter.in.web.workspace.WorkspaceResponses.CreateWorkspaceResponse;
import com.personal.baton.adapter.in.web.workspace.WorkspaceResponses.DecisionResponse;
import com.personal.baton.adapter.in.web.workspace.WorkspaceResponses.HandoffItemResponse;
import com.personal.baton.adapter.in.web.workspace.WorkspaceResponses.MemberResponse;
import com.personal.baton.adapter.in.web.workspace.WorkspaceResponses.RoleResponse;
import com.personal.baton.adapter.in.web.workspace.WorkspaceResponses.RoleHandoffTransitionResponse;
import com.personal.baton.adapter.in.web.workspace.WorkspaceResponses.RoleResourceResponse;
import com.personal.baton.adapter.in.web.workspace.WorkspaceResponses.RoutineExecutionResponse;
import com.personal.baton.adapter.in.web.workspace.WorkspaceResponses.RoutineResponse;
import com.personal.baton.adapter.in.web.workspace.WorkspaceResponses.SeasonRoundResponse;
import com.personal.baton.adapter.in.web.workspace.WorkspaceResponses.WorkspaceResponse;
import com.personal.baton.application.identity.port.in.MemberIdentityUseCase.AuthenticatedAccount;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.personal.baton.adapter.in.web.workspace.WorkspaceControllerSupport.ACCESS_KEY_HEADER;
import static com.personal.baton.adapter.in.web.workspace.WorkspaceControllerSupport.CREATION_KEY_HEADER;
import static com.personal.baton.adapter.in.web.workspace.WorkspaceControllerSupport.IDEMPOTENCY_KEY_HEADER;
import static com.personal.baton.adapter.in.web.workspace.WorkspaceControllerSupport.RECOVERY_KEY_HEADER;
import static com.personal.baton.adapter.in.web.workspace.WorkspaceControllerSupport.authenticatedAccount;
import static com.personal.baton.adapter.in.web.workspace.WorkspaceControllerSupport.invokeAuthorized;

@RestController
@RequestMapping("/api/v1")
public class WorkspaceController {

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

    @PostMapping("/me/workspaces")
    public ResponseEntity<CreateOwnedWorkspaceResponse> createOwnedWorkspace(
            @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            Authentication principal,
            @Valid @RequestBody CreateOwnedWorkspaceRequest request
    ) {
        AuthenticatedAccount authenticatedAccount = authenticatedAccount(principal);
        WorkspaceUseCase.CreatedWorkspaceResult result = workspaceUseCase.createWorkspaceForOwner(
                idempotencyKey,
                authenticatedAccount,
                request.ownerMemberName(),
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
                .body(CreateOwnedWorkspaceResponse.from(result));
    }

    @GetMapping("/teams/{teamId}/seasons/{seasonId}/workspace")
    public ResponseEntity<WorkspaceResponse> getWorkspace(
            @PathVariable UUID teamId,
            @PathVariable UUID seasonId,
            @RequestHeader(name = ACCESS_KEY_HEADER, required = false) String accessKey,
            Authentication principal
    ) {
        WorkspaceResponse response = WorkspaceResponse.from(
                invokeAuthorized(
                        accessKey,
                        principal,
                        key -> workspaceUseCase.getWorkspace(teamId, seasonId, key),
                        authorization -> workspaceUseCase.getWorkspaceAuthorized(
                                teamId,
                                seasonId,
                                authorization
                        )
                )
        );
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    @PostMapping("/teams/{teamId}/seasons/{seasonId}/members")
    public ResponseEntity<MemberResponse> createMember(
            @PathVariable UUID teamId,
            @PathVariable UUID seasonId,
            @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestHeader(name = ACCESS_KEY_HEADER, required = false) String accessKey,
            Authentication principal,
            @Valid @RequestBody CreateMemberRequest request
    ) {
        var command = new WorkspaceUseCase.CreateMemberCommand(request.name());
        WorkspaceUseCase.MemberResult result = invokeAuthorized(
                accessKey,
                principal,
                key -> workspaceUseCase.createMember(
                        teamId,
                        seasonId,
                        idempotencyKey,
                        key,
                        command
                ),
                authorization -> workspaceUseCase.createMemberAuthorized(
                        teamId,
                        seasonId,
                        idempotencyKey,
                        authorization,
                        command
                )
        );
        return ResponseEntity.status(201).body(MemberResponse.from(result));
    }

    @PutMapping("/teams/{teamId}/seasons/{seasonId}/members/{memberId}")
    public MemberResponse updateMember(
            @PathVariable UUID teamId,
            @PathVariable UUID seasonId,
            @PathVariable UUID memberId,
            @RequestHeader(name = ACCESS_KEY_HEADER, required = false) String accessKey,
            Authentication principal,
            @Valid @RequestBody UpdateMemberRequest request
    ) {
        var command = new WorkspaceUseCase.UpdateMemberCommand(request.name());
        return MemberResponse.from(invokeAuthorized(
                accessKey,
                principal,
                key -> workspaceUseCase.updateMember(
                        teamId,
                        seasonId,
                        memberId,
                        key,
                        command
                ),
                authorization -> workspaceUseCase.updateMemberAuthorized(
                        teamId,
                        seasonId,
                        memberId,
                        authorization,
                        command
                )
        ));
    }

    @PatchMapping("/teams/{teamId}/seasons/{seasonId}/members/{memberId}/deactivation")
    public MemberResponse updateMemberDeactivation(
            @PathVariable UUID teamId,
            @PathVariable UUID seasonId,
            @PathVariable UUID memberId,
            @RequestHeader(name = ACCESS_KEY_HEADER, required = false) String accessKey,
            Authentication principal,
            @Valid @RequestBody MemberDeactivationRequest request
    ) {
        return MemberResponse.from(invokeAuthorized(
                accessKey,
                principal,
                key -> workspaceUseCase.updateMemberDeactivation(
                        teamId,
                        seasonId,
                        memberId,
                        key,
                        request.deactivated()
                ),
                authorization -> workspaceUseCase.updateMemberDeactivationAuthorized(
                        teamId,
                        seasonId,
                        memberId,
                        authorization,
                        request.deactivated()
                )
        ));
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
            Authentication principal,
            @Valid @RequestBody CreateRoleRequest request
    ) {
        var command = new WorkspaceUseCase.CreateRoleCommand(
                request.name(),
                request.purpose(),
                request.currentMemberId(),
                request.nextMemberId(),
                request.assignmentStartDate(),
                request.assignmentEndDate(),
                request.responsibilities(),
                request.risk()
        );
        WorkspaceUseCase.RoleResult result = invokeAuthorized(
                accessKey,
                principal,
                key -> workspaceUseCase.createRole(
                        teamId,
                        seasonId,
                        idempotencyKey,
                        key,
                        command
                ),
                authorization -> workspaceUseCase.createRoleAuthorized(
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
        var command = new WorkspaceUseCase.UpdateRoleCommand(
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
                key -> workspaceUseCase.updateRole(
                        teamId,
                        seasonId,
                        roleId,
                        key,
                        command
                ),
                authorization -> workspaceUseCase.updateRoleAuthorized(
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
        var command = new WorkspaceUseCase.PrepareRoleHandoffCommand(
                request.toMemberId(),
                request.incomingAssignmentStartDate(),
                request.incomingAssignmentEndDate()
        );
        WorkspaceUseCase.RoleHandoffTransitionResult result = invokeAuthorized(
                accessKey,
                principal,
                key -> workspaceUseCase.prepareRoleHandoff(
                        teamId,
                        seasonId,
                        roleId,
                        idempotencyKey,
                        key,
                        command
                ),
                authorization -> workspaceUseCase.prepareRoleHandoffAuthorized(
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
        var command = new WorkspaceUseCase.TransferRoleHandoffCommand(
                request.confirmedByMemberId(),
                request.warningAcknowledged()
        );
        return RoleHandoffTransitionResponse.from(invokeAuthorized(
                accessKey,
                principal,
                key -> workspaceUseCase.transferRoleHandoff(
                        teamId,
                        seasonId,
                        roleId,
                        handoffId,
                        key,
                        command
                ),
                authorization -> workspaceUseCase.transferRoleHandoffAuthorized(
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
        var command = new WorkspaceUseCase.ConfirmRoleHandoffCommand(
                request.confirmedByMemberId()
        );
        return RoleHandoffTransitionResponse.from(invokeAuthorized(
                accessKey,
                principal,
                key -> workspaceUseCase.acceptRoleHandoff(
                        teamId,
                        seasonId,
                        roleId,
                        handoffId,
                        key,
                        command
                ),
                authorization -> workspaceUseCase.acceptRoleHandoffAuthorized(
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
        var command = new WorkspaceUseCase.ConfirmRoleHandoffCommand(
                request.confirmedByMemberId()
        );
        return RoleHandoffTransitionResponse.from(invokeAuthorized(
                accessKey,
                principal,
                key -> workspaceUseCase.cancelRoleHandoff(
                        teamId,
                        seasonId,
                        roleId,
                        handoffId,
                        key,
                        command
                ),
                authorization -> workspaceUseCase.cancelRoleHandoffAuthorized(
                        teamId,
                        seasonId,
                        roleId,
                        handoffId,
                        authorization,
                        command
                )
        ));
    }

    @PostMapping("/teams/{teamId}/seasons/{seasonId}/routines")
    public ResponseEntity<RoutineResponse> createRoutine(
            @PathVariable UUID teamId,
            @PathVariable UUID seasonId,
            @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestHeader(name = ACCESS_KEY_HEADER, required = false) String accessKey,
            Authentication principal,
            @Valid @RequestBody CreateRoutineRequest request
    ) {
        var command = new WorkspaceUseCase.CreateRoutineCommand(
                request.title(),
                request.phase(),
                request.dueLabel(),
                request.ownerRoleId(),
                request.detail(),
                request.deadlineDayOffset(),
                request.deadlineTime()
        );
        WorkspaceUseCase.RoutineResult result = invokeAuthorized(
                accessKey,
                principal,
                key -> workspaceUseCase.createRoutine(
                        teamId,
                        seasonId,
                        idempotencyKey,
                        key,
                        command
                ),
                authorization -> workspaceUseCase.createRoutineAuthorized(
                        teamId,
                        seasonId,
                        idempotencyKey,
                        authorization,
                        command
                )
        );
        return ResponseEntity.status(201).body(RoutineResponse.from(result));
    }

    @PutMapping("/teams/{teamId}/seasons/{seasonId}/routines/{routineId}")
    public RoutineResponse updateRoutine(
            @PathVariable UUID teamId,
            @PathVariable UUID seasonId,
            @PathVariable UUID routineId,
            @RequestHeader(name = ACCESS_KEY_HEADER, required = false) String accessKey,
            Authentication principal,
            @Valid @RequestBody UpdateRoutineRequest request
    ) {
        var command = new WorkspaceUseCase.UpdateRoutineCommand(
                request.title(),
                request.phase(),
                request.dueLabel(),
                request.ownerRoleId(),
                request.detail(),
                request.deadlineDayOffset(),
                request.deadlineTime()
        );
        return RoutineResponse.from(invokeAuthorized(
                accessKey,
                principal,
                key -> workspaceUseCase.updateRoutine(
                        teamId,
                        seasonId,
                        routineId,
                        key,
                        command
                ),
                authorization -> workspaceUseCase.updateRoutineAuthorized(
                        teamId,
                        seasonId,
                        routineId,
                        authorization,
                        command
                )
        ));
    }

    @PostMapping("/teams/{teamId}/seasons/{seasonId}/rounds")
    public ResponseEntity<SeasonRoundResponse> createSeasonRound(
            @PathVariable UUID teamId,
            @PathVariable UUID seasonId,
            @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestHeader(name = ACCESS_KEY_HEADER, required = false) String accessKey,
            Authentication principal,
            @Valid @RequestBody CreateSeasonRoundRequest request
    ) {
        var command = new WorkspaceUseCase.CreateSeasonRoundCommand(
                request.name(),
                request.meetingDate()
        );
        WorkspaceUseCase.SeasonRoundResult result = invokeAuthorized(
                accessKey,
                principal,
                key -> workspaceUseCase.createSeasonRound(
                        teamId,
                        seasonId,
                        idempotencyKey,
                        key,
                        command
                ),
                authorization -> workspaceUseCase.createSeasonRoundAuthorized(
                        teamId,
                        seasonId,
                        idempotencyKey,
                        authorization,
                        command
                )
        );
        return ResponseEntity.status(201).body(SeasonRoundResponse.from(result));
    }

    @PutMapping("/teams/{teamId}/seasons/{seasonId}/rounds/{roundId}")
    public SeasonRoundResponse updateSeasonRound(
            @PathVariable UUID teamId,
            @PathVariable UUID seasonId,
            @PathVariable UUID roundId,
            @RequestHeader(name = ACCESS_KEY_HEADER, required = false) String accessKey,
            Authentication principal,
            @Valid @RequestBody UpdateSeasonRoundRequest request
    ) {
        var command = new WorkspaceUseCase.UpdateSeasonRoundCommand(
                request.name(),
                request.meetingDate()
        );
        return SeasonRoundResponse.from(invokeAuthorized(
                accessKey,
                principal,
                key -> workspaceUseCase.updateSeasonRound(
                        teamId,
                        seasonId,
                        roundId,
                        key,
                        command
                ),
                authorization -> workspaceUseCase.updateSeasonRoundAuthorized(
                        teamId,
                        seasonId,
                        roundId,
                        authorization,
                        command
                )
        ));
    }

    @PatchMapping("/teams/{teamId}/seasons/{seasonId}/rounds/{roundId}/archive")
    public SeasonRoundResponse updateSeasonRoundArchive(
            @PathVariable UUID teamId,
            @PathVariable UUID seasonId,
            @PathVariable UUID roundId,
            @RequestHeader(name = ACCESS_KEY_HEADER, required = false) String accessKey,
            Authentication principal,
            @Valid @RequestBody ArchiveRequest request
    ) {
        return SeasonRoundResponse.from(invokeAuthorized(
                accessKey,
                principal,
                key -> workspaceUseCase.updateSeasonRoundArchive(
                        teamId,
                        seasonId,
                        roundId,
                        key,
                        request.archived()
                ),
                authorization -> workspaceUseCase.updateSeasonRoundArchiveAuthorized(
                        teamId,
                        seasonId,
                        roundId,
                        authorization,
                        request.archived()
                )
        ));
    }

    @PatchMapping(
            "/teams/{teamId}/seasons/{seasonId}/rounds/{roundId}/routine-executions/{executionId}/completion"
    )
    public RoutineExecutionResponse updateRoutineExecutionCompletion(
            @PathVariable UUID teamId,
            @PathVariable UUID seasonId,
            @PathVariable UUID roundId,
            @PathVariable UUID executionId,
            @RequestHeader(name = ACCESS_KEY_HEADER, required = false) String accessKey,
            Authentication principal,
            @Valid @RequestBody UpdateRoutineExecutionCompletionRequest request
    ) {
        return RoutineExecutionResponse.from(invokeAuthorized(
                accessKey,
                principal,
                key -> workspaceUseCase.updateRoutineExecutionCompletion(
                        teamId,
                        seasonId,
                        roundId,
                        executionId,
                        key,
                        request.completed()
                ),
                authorization -> workspaceUseCase.updateRoutineExecutionCompletionAuthorized(
                        teamId,
                        seasonId,
                        roundId,
                        executionId,
                        authorization,
                        request.completed()
                )
        ));
    }

    @PostMapping("/teams/{teamId}/seasons/{seasonId}/decisions")
    public ResponseEntity<DecisionResponse> createDecision(
            @PathVariable UUID teamId,
            @PathVariable UUID seasonId,
            @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestHeader(name = ACCESS_KEY_HEADER, required = false) String accessKey,
            Authentication principal,
            @Valid @RequestBody CreateDecisionRequest request
    ) {
        var command = new WorkspaceUseCase.CreateDecisionCommand(
                request.title(),
                request.reason(),
                request.alternative(),
                request.authorMemberId(),
                request.roleIds()
        );
        WorkspaceUseCase.DecisionResult result = invokeAuthorized(
                accessKey,
                principal,
                key -> workspaceUseCase.createDecision(
                        teamId,
                        seasonId,
                        idempotencyKey,
                        key,
                        command
                ),
                authorization -> workspaceUseCase.createDecisionAuthorized(
                        teamId,
                        seasonId,
                        idempotencyKey,
                        authorization,
                        command
                )
        );
        return ResponseEntity.status(201).body(DecisionResponse.from(result));
    }

    @PutMapping("/teams/{teamId}/seasons/{seasonId}/decisions/{decisionId}")
    public DecisionResponse updateDecision(
            @PathVariable UUID teamId,
            @PathVariable UUID seasonId,
            @PathVariable UUID decisionId,
            @RequestHeader(name = ACCESS_KEY_HEADER, required = false) String accessKey,
            Authentication principal,
            @Valid @RequestBody UpdateDecisionRequest request
    ) {
        var command = new WorkspaceUseCase.UpdateDecisionCommand(
                request.title(),
                request.reason(),
                request.alternative(),
                request.authorMemberId(),
                request.roleIds()
        );
        return DecisionResponse.from(invokeAuthorized(
                accessKey,
                principal,
                key -> workspaceUseCase.updateDecision(
                        teamId,
                        seasonId,
                        decisionId,
                        key,
                        command
                ),
                authorization -> workspaceUseCase.updateDecisionAuthorized(
                        teamId,
                        seasonId,
                        decisionId,
                        authorization,
                        command
                )
        ));
    }

    @PatchMapping("/teams/{teamId}/seasons/{seasonId}/decisions/{decisionId}/archive")
    public DecisionResponse updateDecisionArchive(
            @PathVariable UUID teamId,
            @PathVariable UUID seasonId,
            @PathVariable UUID decisionId,
            @RequestHeader(name = ACCESS_KEY_HEADER, required = false) String accessKey,
            Authentication principal,
            @Valid @RequestBody ArchiveRequest request
    ) {
        return DecisionResponse.from(invokeAuthorized(
                accessKey,
                principal,
                key -> workspaceUseCase.updateDecisionArchive(
                        teamId,
                        seasonId,
                        decisionId,
                        key,
                        request.archived()
                ),
                authorization -> workspaceUseCase.updateDecisionArchiveAuthorized(
                        teamId,
                        seasonId,
                        decisionId,
                        authorization,
                        request.archived()
                )
        ));
    }

    @PostMapping("/teams/{teamId}/seasons/{seasonId}/handoff-items")
    public ResponseEntity<HandoffItemResponse> createHandoffItem(
            @PathVariable UUID teamId,
            @PathVariable UUID seasonId,
            @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestHeader(name = ACCESS_KEY_HEADER, required = false) String accessKey,
            Authentication principal,
            @Valid @RequestBody CreateHandoffItemRequest request
    ) {
        var command = new WorkspaceUseCase.CreateHandoffItemCommand(
                request.roleId(),
                request.label(),
                request.category()
        );
        WorkspaceUseCase.HandoffItemResult result = invokeAuthorized(
                accessKey,
                principal,
                key -> workspaceUseCase.createHandoffItem(
                        teamId,
                        seasonId,
                        idempotencyKey,
                        key,
                        command
                ),
                authorization -> workspaceUseCase.createHandoffItemAuthorized(
                        teamId,
                        seasonId,
                        idempotencyKey,
                        authorization,
                        command
                )
        );
        return ResponseEntity.status(201).body(HandoffItemResponse.from(result));
    }

    @PutMapping("/teams/{teamId}/seasons/{seasonId}/handoff-items/{itemId}")
    public HandoffItemResponse updateHandoffItem(
            @PathVariable UUID teamId,
            @PathVariable UUID seasonId,
            @PathVariable UUID itemId,
            @RequestHeader(name = ACCESS_KEY_HEADER, required = false) String accessKey,
            Authentication principal,
            @Valid @RequestBody UpdateHandoffItemRequest request
    ) {
        var command = new WorkspaceUseCase.UpdateHandoffItemCommand(
                request.roleId(),
                request.label(),
                request.category()
        );
        return HandoffItemResponse.from(invokeAuthorized(
                accessKey,
                principal,
                key -> workspaceUseCase.updateHandoffItem(
                        teamId,
                        seasonId,
                        itemId,
                        key,
                        command
                ),
                authorization -> workspaceUseCase.updateHandoffItemAuthorized(
                        teamId,
                        seasonId,
                        itemId,
                        authorization,
                        command
                )
        ));
    }

    @PatchMapping("/teams/{teamId}/seasons/{seasonId}/handoff-items/{itemId}/completion")
    public HandoffItemResponse updateHandoffItemCompletion(
            @PathVariable UUID teamId,
            @PathVariable UUID seasonId,
            @PathVariable UUID itemId,
            @RequestHeader(name = ACCESS_KEY_HEADER, required = false) String accessKey,
            Authentication principal,
            @Valid @RequestBody CompletionRequest request
    ) {
        return HandoffItemResponse.from(invokeAuthorized(
                accessKey,
                principal,
                key -> workspaceUseCase.updateHandoffItemCompletion(
                        teamId,
                        seasonId,
                        itemId,
                        key,
                        request.completed()
                ),
                authorization -> workspaceUseCase.updateHandoffItemCompletionAuthorized(
                        teamId,
                        seasonId,
                        itemId,
                        authorization,
                        request.completed()
                )
        ));
    }

    @PatchMapping("/teams/{teamId}/seasons/{seasonId}/handoff-items/{itemId}/archive")
    public HandoffItemResponse updateHandoffItemArchive(
            @PathVariable UUID teamId,
            @PathVariable UUID seasonId,
            @PathVariable UUID itemId,
            @RequestHeader(name = ACCESS_KEY_HEADER, required = false) String accessKey,
            Authentication principal,
            @Valid @RequestBody ArchiveRequest request
    ) {
        return HandoffItemResponse.from(invokeAuthorized(
                accessKey,
                principal,
                key -> workspaceUseCase.updateHandoffItemArchive(
                        teamId,
                        seasonId,
                        itemId,
                        key,
                        request.archived()
                ),
                authorization -> workspaceUseCase.updateHandoffItemArchiveAuthorized(
                        teamId,
                        seasonId,
                        itemId,
                        authorization,
                        request.archived()
                )
        ));
    }

    @PostMapping("/teams/{teamId}/seasons/{seasonId}/role-resources")
    public ResponseEntity<RoleResourceResponse> createRoleResource(
            @PathVariable UUID teamId,
            @PathVariable UUID seasonId,
            @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestHeader(name = ACCESS_KEY_HEADER, required = false) String accessKey,
            Authentication principal,
            @Valid @RequestBody CreateRoleResourceRequest request
    ) {
        var command = new WorkspaceUseCase.CreateRoleResourceCommand(
                request.roleId(),
                request.title(),
                request.url(),
                request.description()
        );
        WorkspaceUseCase.RoleResourceResult result = invokeAuthorized(
                accessKey,
                principal,
                key -> workspaceUseCase.createRoleResource(
                        teamId,
                        seasonId,
                        idempotencyKey,
                        key,
                        command
                ),
                authorization -> workspaceUseCase.createRoleResourceAuthorized(
                        teamId,
                        seasonId,
                        idempotencyKey,
                        authorization,
                        command
                )
        );
        return ResponseEntity.status(201).body(RoleResourceResponse.from(result));
    }

    @PutMapping("/teams/{teamId}/seasons/{seasonId}/role-resources/{resourceId}")
    public RoleResourceResponse updateRoleResource(
            @PathVariable UUID teamId,
            @PathVariable UUID seasonId,
            @PathVariable UUID resourceId,
            @RequestHeader(name = ACCESS_KEY_HEADER, required = false) String accessKey,
            Authentication principal,
            @Valid @RequestBody UpdateRoleResourceRequest request
    ) {
        var command = new WorkspaceUseCase.UpdateRoleResourceCommand(
                request.roleId(),
                request.title(),
                request.url(),
                request.description()
        );
        return RoleResourceResponse.from(invokeAuthorized(
                accessKey,
                principal,
                key -> workspaceUseCase.updateRoleResource(
                        teamId,
                        seasonId,
                        resourceId,
                        key,
                        command
                ),
                authorization -> workspaceUseCase.updateRoleResourceAuthorized(
                        teamId,
                        seasonId,
                        resourceId,
                        authorization,
                        command
                )
        ));
    }

    private ResponseEntity<AccessKeyResponse> noStoreAccessKey(WorkspaceUseCase.AccessKeyResult result) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(AccessKeyResponse.from(result));
    }
}
