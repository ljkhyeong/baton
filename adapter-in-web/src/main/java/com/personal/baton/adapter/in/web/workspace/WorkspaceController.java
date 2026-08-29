package com.personal.baton.adapter.in.web.workspace;

import com.personal.baton.adapter.in.web.workspace.WorkspaceRequests.CompletionRequest;
import com.personal.baton.adapter.in.web.workspace.WorkspaceRequests.ArchiveRequest;
import com.personal.baton.adapter.in.web.workspace.WorkspaceRequests.ConfirmRoleHandoffRequest;
import com.personal.baton.adapter.in.web.workspace.WorkspaceRequests.CreateDecisionRequest;
import com.personal.baton.adapter.in.web.workspace.WorkspaceRequests.CreateHandoffItemRequest;
import com.personal.baton.adapter.in.web.workspace.WorkspaceRequests.CreateMemberRequest;
import com.personal.baton.adapter.in.web.workspace.WorkspaceRequests.CreateRoleRequest;
import com.personal.baton.adapter.in.web.workspace.WorkspaceRequests.CreateRoleResourceRequest;
import com.personal.baton.adapter.in.web.workspace.WorkspaceRequests.CreateRoutineRequest;
import com.personal.baton.adapter.in.web.workspace.WorkspaceRequests.CreateNextSeasonRequest;
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
import com.personal.baton.adapter.in.web.workspace.WorkspaceRequests.UpdateSeasonEndingRequest;
import com.personal.baton.adapter.in.web.workspace.WorkspaceRequests.UpdateSeasonRequest;
import com.personal.baton.adapter.in.web.workspace.WorkspaceRequests.UpdateSeasonRoundRequest;
import com.personal.baton.adapter.in.web.workspace.WorkspaceRequests.UpdateRoundScheduleRequest;
import com.personal.baton.adapter.in.web.workspace.WorkspaceRequests.UpdateDecisionRequest;
import com.personal.baton.adapter.in.web.workspace.WorkspaceRequests.UpdateHandoffItemRequest;
import com.personal.baton.adapter.in.web.workspace.WorkspaceResponses.AccessKeyResponse;
import com.personal.baton.adapter.in.web.workspace.WorkspaceResponses.CreateWorkspaceResponse;
import com.personal.baton.adapter.in.web.workspace.WorkspaceResponses.DecisionResponse;
import com.personal.baton.adapter.in.web.workspace.WorkspaceResponses.HandoffItemResponse;
import com.personal.baton.adapter.in.web.workspace.WorkspaceResponses.MemberResponse;
import com.personal.baton.adapter.in.web.workspace.WorkspaceResponses.NextSeasonResponse;
import com.personal.baton.adapter.in.web.workspace.WorkspaceResponses.RoleResponse;
import com.personal.baton.adapter.in.web.workspace.WorkspaceResponses.RoleHandoffTransitionResponse;
import com.personal.baton.adapter.in.web.workspace.WorkspaceResponses.RoleResourceResponse;
import com.personal.baton.adapter.in.web.workspace.WorkspaceResponses.RoutineExecutionResponse;
import com.personal.baton.adapter.in.web.workspace.WorkspaceResponses.RoutineResponse;
import com.personal.baton.adapter.in.web.workspace.WorkspaceResponses.SeasonRoundResponse;
import com.personal.baton.adapter.in.web.workspace.WorkspaceResponses.SeasonResponse;
import com.personal.baton.adapter.in.web.workspace.WorkspaceResponses.WorkspaceResponse;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
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
        URI location = workspaceLocation(result.teamId(), result.seasonId());
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

    @PutMapping("/teams/{teamId}/seasons/{seasonId}")
    public SeasonResponse updateSeason(
            @PathVariable UUID teamId,
            @PathVariable UUID seasonId,
            @RequestHeader(name = ACCESS_KEY_HEADER, required = false) String accessKey,
            @Valid @RequestBody UpdateSeasonRequest request
    ) {
        return SeasonResponse.from(workspaceUseCase.updateSeason(
                teamId,
                seasonId,
                accessKey,
                new WorkspaceUseCase.UpdateSeasonCommand(
                        request.name(),
                        request.startDate(),
                        request.endDate()
                )
        ));
    }

    @PutMapping("/teams/{teamId}/seasons/{seasonId}/round-schedule")
    public SeasonResponse updateRoundSchedule(
            @PathVariable UUID teamId,
            @PathVariable UUID seasonId,
            @RequestHeader(name = ACCESS_KEY_HEADER, required = false) String accessKey,
            @Valid @RequestBody UpdateRoundScheduleRequest request
    ) {
        return SeasonResponse.from(workspaceUseCase.updateRoundSchedule(
                teamId,
                seasonId,
                accessKey,
                new WorkspaceUseCase.UpdateRoundScheduleCommand(
                        request.timeZone(),
                        request.firstMeetingDate(),
                        request.meetingTime(),
                        request.recurrence(),
                        request.generationLeadDays(),
                        request.enabled()
                )
        ));
    }

    @PatchMapping("/teams/{teamId}/seasons/{seasonId}/ending")
    public SeasonResponse updateSeasonEnding(
            @PathVariable UUID teamId,
            @PathVariable UUID seasonId,
            @RequestHeader(name = ACCESS_KEY_HEADER, required = false) String accessKey,
            @Valid @RequestBody UpdateSeasonEndingRequest request
    ) {
        return SeasonResponse.from(workspaceUseCase.updateSeasonEnding(
                teamId,
                seasonId,
                accessKey,
                request.ended()
        ));
    }

    @PostMapping("/teams/{teamId}/seasons/{seasonId}/successor")
    public ResponseEntity<NextSeasonResponse> createNextSeason(
            @PathVariable UUID teamId,
            @PathVariable UUID seasonId,
            @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestHeader(name = ACCESS_KEY_HEADER, required = false) String accessKey,
            @Valid @RequestBody CreateNextSeasonRequest request
    ) {
        WorkspaceUseCase.NextSeasonResult result = workspaceUseCase.createNextSeason(
                teamId,
                seasonId,
                idempotencyKey,
                accessKey,
                new WorkspaceUseCase.CreateNextSeasonCommand(
                        request.name(),
                        request.startDate(),
                        request.endDate(),
                        request.copyRoleIds(),
                        request.copyRoutineIds()
                )
        );
        URI location = workspaceLocation(teamId, result.season().id());
        return ResponseEntity.created(location).body(NextSeasonResponse.from(result));
    }

    @PostMapping("/teams/{teamId}/seasons/{seasonId}/members")
    @ResponseStatus(HttpStatus.CREATED)
    public MemberResponse createMember(
            @PathVariable UUID teamId,
            @PathVariable UUID seasonId,
            @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestHeader(name = ACCESS_KEY_HEADER, required = false) String accessKey,
            @Valid @RequestBody CreateMemberRequest request
    ) {
        WorkspaceUseCase.MemberResult result = workspaceUseCase.createMember(
                teamId,
                seasonId,
                idempotencyKey,
                accessKey,
                new WorkspaceUseCase.CreateMemberCommand(request.name())
        );
        return MemberResponse.from(result);
    }

    @PutMapping("/teams/{teamId}/seasons/{seasonId}/members/{memberId}")
    public MemberResponse updateMember(
            @PathVariable UUID teamId,
            @PathVariable UUID seasonId,
            @PathVariable UUID memberId,
            @RequestHeader(name = ACCESS_KEY_HEADER, required = false) String accessKey,
            @Valid @RequestBody UpdateMemberRequest request
    ) {
        return MemberResponse.from(workspaceUseCase.updateMember(
                teamId,
                seasonId,
                memberId,
                accessKey,
                new WorkspaceUseCase.UpdateMemberCommand(request.name())
        ));
    }

    @PatchMapping("/teams/{teamId}/seasons/{seasonId}/members/{memberId}/deactivation")
    public MemberResponse updateMemberDeactivation(
            @PathVariable UUID teamId,
            @PathVariable UUID seasonId,
            @PathVariable UUID memberId,
            @RequestHeader(name = ACCESS_KEY_HEADER, required = false) String accessKey,
            @Valid @RequestBody MemberDeactivationRequest request
    ) {
        return MemberResponse.from(workspaceUseCase.updateMemberDeactivation(
                teamId,
                seasonId,
                memberId,
                accessKey,
                request.deactivated()
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
    @ResponseStatus(HttpStatus.CREATED)
    public RoleResponse createRole(
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
        return RoleResponse.from(result);
    }

    @PutMapping("/teams/{teamId}/seasons/{seasonId}/roles/{roleId}")
    public RoleResponse updateRole(
            @PathVariable UUID teamId,
            @PathVariable UUID seasonId,
            @PathVariable UUID roleId,
            @RequestHeader(name = ACCESS_KEY_HEADER, required = false) String accessKey,
            @Valid @RequestBody UpdateRoleRequest request
    ) {
        return RoleResponse.from(workspaceUseCase.updateRole(
                teamId,
                seasonId,
                roleId,
                accessKey,
                new WorkspaceUseCase.UpdateRoleCommand(
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
            @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestHeader(name = ACCESS_KEY_HEADER, required = false) String accessKey,
            @Valid @RequestBody PrepareRoleHandoffRequest request
    ) {
        WorkspaceUseCase.RoleHandoffTransitionResult result = workspaceUseCase.prepareRoleHandoff(
                teamId,
                seasonId,
                roleId,
                idempotencyKey,
                accessKey,
                new WorkspaceUseCase.PrepareRoleHandoffCommand(
                        request.toMemberId(),
                        request.incomingAssignmentStartDate(),
                        request.incomingAssignmentEndDate()
                )
        );
        URI location = roleHandoffLocation(
                teamId,
                seasonId,
                roleId,
                result.handoff().id()
        );
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
            @Valid @RequestBody TransferRoleHandoffRequest request
    ) {
        return RoleHandoffTransitionResponse.from(workspaceUseCase.transferRoleHandoff(
                teamId,
                seasonId,
                roleId,
                handoffId,
                accessKey,
                new WorkspaceUseCase.TransferRoleHandoffCommand(
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
            @RequestHeader(name = ACCESS_KEY_HEADER, required = false) String accessKey,
            @Valid @RequestBody ConfirmRoleHandoffRequest request
    ) {
        return RoleHandoffTransitionResponse.from(workspaceUseCase.acceptRoleHandoff(
                teamId,
                seasonId,
                roleId,
                handoffId,
                accessKey,
                new WorkspaceUseCase.ConfirmRoleHandoffCommand(request.confirmedByMemberId())
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
            @Valid @RequestBody ConfirmRoleHandoffRequest request
    ) {
        return RoleHandoffTransitionResponse.from(workspaceUseCase.cancelRoleHandoff(
                teamId,
                seasonId,
                roleId,
                handoffId,
                accessKey,
                new WorkspaceUseCase.ConfirmRoleHandoffCommand(request.confirmedByMemberId())
        ));
    }

    @PostMapping("/teams/{teamId}/seasons/{seasonId}/routines")
    @ResponseStatus(HttpStatus.CREATED)
    public RoutineResponse createRoutine(
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
                        request.detail(),
                        request.deadlineDayOffset(),
                        request.deadlineTime()
                )
        );
        return RoutineResponse.from(result);
    }

    @PutMapping("/teams/{teamId}/seasons/{seasonId}/routines/{routineId}")
    public RoutineResponse updateRoutine(
            @PathVariable UUID teamId,
            @PathVariable UUID seasonId,
            @PathVariable UUID routineId,
            @RequestHeader(name = ACCESS_KEY_HEADER, required = false) String accessKey,
            @Valid @RequestBody UpdateRoutineRequest request
    ) {
        return RoutineResponse.from(workspaceUseCase.updateRoutine(
                teamId,
                seasonId,
                routineId,
                accessKey,
                new WorkspaceUseCase.UpdateRoutineCommand(
                        request.title(),
                        request.phase(),
                        request.dueLabel(),
                        request.ownerRoleId(),
                        request.detail(),
                        request.deadlineDayOffset(),
                        request.deadlineTime()
                )
        ));
    }

    @PatchMapping("/teams/{teamId}/seasons/{seasonId}/routines/{routineId}/archive")
    public RoutineResponse updateRoutineArchive(
            @PathVariable UUID teamId,
            @PathVariable UUID seasonId,
            @PathVariable UUID routineId,
            @RequestHeader(name = ACCESS_KEY_HEADER, required = false) String accessKey,
            @Valid @RequestBody ArchiveRequest request
    ) {
        return RoutineResponse.from(workspaceUseCase.updateRoutineArchive(
                teamId,
                seasonId,
                routineId,
                accessKey,
                request.archived()
        ));
    }

    @PostMapping("/teams/{teamId}/seasons/{seasonId}/rounds")
    @ResponseStatus(HttpStatus.CREATED)
    public SeasonRoundResponse createSeasonRound(
            @PathVariable UUID teamId,
            @PathVariable UUID seasonId,
            @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestHeader(name = ACCESS_KEY_HEADER, required = false) String accessKey,
            @Valid @RequestBody CreateSeasonRoundRequest request
    ) {
        WorkspaceUseCase.SeasonRoundResult result = workspaceUseCase.createSeasonRound(
                teamId,
                seasonId,
                idempotencyKey,
                accessKey,
                new WorkspaceUseCase.CreateSeasonRoundCommand(request.name(), request.meetingDate())
        );
        return SeasonRoundResponse.from(result);
    }

    @PutMapping("/teams/{teamId}/seasons/{seasonId}/rounds/{roundId}")
    public SeasonRoundResponse updateSeasonRound(
            @PathVariable UUID teamId,
            @PathVariable UUID seasonId,
            @PathVariable UUID roundId,
            @RequestHeader(name = ACCESS_KEY_HEADER, required = false) String accessKey,
            @Valid @RequestBody UpdateSeasonRoundRequest request
    ) {
        return SeasonRoundResponse.from(workspaceUseCase.updateSeasonRound(
                teamId,
                seasonId,
                roundId,
                accessKey,
                new WorkspaceUseCase.UpdateSeasonRoundCommand(request.name(), request.meetingDate())
        ));
    }

    @PatchMapping("/teams/{teamId}/seasons/{seasonId}/rounds/{roundId}/archive")
    public SeasonRoundResponse updateSeasonRoundArchive(
            @PathVariable UUID teamId,
            @PathVariable UUID seasonId,
            @PathVariable UUID roundId,
            @RequestHeader(name = ACCESS_KEY_HEADER, required = false) String accessKey,
            @Valid @RequestBody ArchiveRequest request
    ) {
        return SeasonRoundResponse.from(workspaceUseCase.updateSeasonRoundArchive(
                teamId,
                seasonId,
                roundId,
                accessKey,
                request.archived()
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
            @Valid @RequestBody UpdateRoutineExecutionCompletionRequest request
    ) {
        return RoutineExecutionResponse.from(workspaceUseCase.updateRoutineExecutionCompletion(
                teamId,
                seasonId,
                roundId,
                executionId,
                accessKey,
                request.completed()
        ));
    }

    @PostMapping("/teams/{teamId}/seasons/{seasonId}/decisions")
    @ResponseStatus(HttpStatus.CREATED)
    public DecisionResponse createDecision(
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
        return DecisionResponse.from(result);
    }

    @PutMapping("/teams/{teamId}/seasons/{seasonId}/decisions/{decisionId}")
    public DecisionResponse updateDecision(
            @PathVariable UUID teamId,
            @PathVariable UUID seasonId,
            @PathVariable UUID decisionId,
            @RequestHeader(name = ACCESS_KEY_HEADER, required = false) String accessKey,
            @Valid @RequestBody UpdateDecisionRequest request
    ) {
        return DecisionResponse.from(workspaceUseCase.updateDecision(
                teamId,
                seasonId,
                decisionId,
                accessKey,
                new WorkspaceUseCase.UpdateDecisionCommand(
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
            @RequestHeader(name = ACCESS_KEY_HEADER, required = false) String accessKey,
            @Valid @RequestBody ArchiveRequest request
    ) {
        return DecisionResponse.from(workspaceUseCase.updateDecisionArchive(
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
        return HandoffItemResponse.from(result);
    }

    @PutMapping("/teams/{teamId}/seasons/{seasonId}/handoff-items/{itemId}")
    public HandoffItemResponse updateHandoffItem(
            @PathVariable UUID teamId,
            @PathVariable UUID seasonId,
            @PathVariable UUID itemId,
            @RequestHeader(name = ACCESS_KEY_HEADER, required = false) String accessKey,
            @Valid @RequestBody UpdateHandoffItemRequest request
    ) {
        return HandoffItemResponse.from(workspaceUseCase.updateHandoffItem(
                teamId,
                seasonId,
                itemId,
                accessKey,
                new WorkspaceUseCase.UpdateHandoffItemCommand(
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
            @RequestHeader(name = ACCESS_KEY_HEADER, required = false) String accessKey,
            @Valid @RequestBody CompletionRequest request
    ) {
        return HandoffItemResponse.from(workspaceUseCase.updateHandoffItemCompletion(
                teamId, seasonId, itemId, accessKey, request.completed()));
    }

    @PatchMapping("/teams/{teamId}/seasons/{seasonId}/handoff-items/{itemId}/archive")
    public HandoffItemResponse updateHandoffItemArchive(
            @PathVariable UUID teamId,
            @PathVariable UUID seasonId,
            @PathVariable UUID itemId,
            @RequestHeader(name = ACCESS_KEY_HEADER, required = false) String accessKey,
            @Valid @RequestBody ArchiveRequest request
    ) {
        return HandoffItemResponse.from(workspaceUseCase.updateHandoffItemArchive(
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
            @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestHeader(name = ACCESS_KEY_HEADER, required = false) String accessKey,
            @Valid @RequestBody CreateRoleResourceRequest request
    ) {
        WorkspaceUseCase.RoleResourceResult result = workspaceUseCase.createRoleResource(
                teamId,
                seasonId,
                idempotencyKey,
                accessKey,
                new WorkspaceUseCase.CreateRoleResourceCommand(
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
            @RequestHeader(name = ACCESS_KEY_HEADER, required = false) String accessKey,
            @Valid @RequestBody UpdateRoleResourceRequest request
    ) {
        return RoleResourceResponse.from(workspaceUseCase.updateRoleResource(
                teamId,
                seasonId,
                resourceId,
                accessKey,
                new WorkspaceUseCase.UpdateRoleResourceCommand(
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
            @RequestHeader(name = ACCESS_KEY_HEADER, required = false) String accessKey,
            @Valid @RequestBody ArchiveRequest request
    ) {
        return RoleResourceResponse.from(workspaceUseCase.updateRoleResourceArchive(
                teamId,
                seasonId,
                resourceId,
                accessKey,
                request.archived()
        ));
    }

    private ResponseEntity<AccessKeyResponse> noStoreAccessKey(WorkspaceUseCase.AccessKeyResult result) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(AccessKeyResponse.from(result));
    }

    private URI workspaceLocation(UUID teamId, UUID seasonId) {
        return UriComponentsBuilder
                .fromPath("/api/v1/teams/{teamId}/seasons/{seasonId}/workspace")
                .build(teamId, seasonId);
    }

    private URI roleHandoffLocation(
            UUID teamId,
            UUID seasonId,
            UUID roleId,
            UUID handoffId
    ) {
        return UriComponentsBuilder
                .fromPath(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}"
                                + "/roles/{roleId}/handoffs/{handoffId}"
                )
                .build(teamId, seasonId, roleId, handoffId);
    }
}
