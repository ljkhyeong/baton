package com.personal.baton.adapter.in.web.workspace;

import com.personal.baton.adapter.in.web.workspace.WorkspaceRequests.ArchiveRequest;
import com.personal.baton.adapter.in.web.workspace.WorkspaceRequests.CreateRoutineRequest;
import com.personal.baton.adapter.in.web.workspace.WorkspaceRequests.CreateSeasonRoundRequest;
import com.personal.baton.adapter.in.web.workspace.WorkspaceRequests.UpdateRoutineExecutionCompletionRequest;
import com.personal.baton.adapter.in.web.workspace.WorkspaceRequests.UpdateRoutineRequest;
import com.personal.baton.adapter.in.web.workspace.WorkspaceRequests.UpdateSeasonRoundRequest;
import com.personal.baton.adapter.in.web.workspace.WorkspaceResponses.RoutineExecutionResponse;
import com.personal.baton.adapter.in.web.workspace.WorkspaceResponses.RoutineResponse;
import com.personal.baton.adapter.in.web.workspace.WorkspaceResponses.SeasonRoundResponse;
import com.personal.baton.application.workspace.port.in.WorkspaceContract;
import com.personal.baton.application.workspace.port.in.WorkspaceOperationsUseCase;
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
public class WorkspaceOperationsController {

    private final WorkspaceOperationsUseCase operationsUseCase;

    public WorkspaceOperationsController(WorkspaceOperationsUseCase operationsUseCase) {
        this.operationsUseCase = operationsUseCase;
    }

    @PostMapping("/teams/{teamId}/seasons/{seasonId}/routines")
    @ResponseStatus(HttpStatus.CREATED)
    public RoutineResponse createRoutine(
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
            @Valid @RequestBody CreateRoutineRequest request
    ) {
        WorkspaceContract.RoutineResult result = operationsUseCase.createRoutine(
                teamId,
                seasonId,
                idempotencyKey,
                accessKey,
                new WorkspaceContract.CreateRoutineCommand(
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
            @RequestHeader(
                    name = WorkspaceLifecycleController.ACCESS_KEY_HEADER,
                    required = false
            ) String accessKey,
            @Valid @RequestBody UpdateRoutineRequest request
    ) {
        return RoutineResponse.from(operationsUseCase.updateRoutine(
                teamId,
                seasonId,
                routineId,
                accessKey,
                new WorkspaceContract.UpdateRoutineCommand(
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
            @RequestHeader(
                    name = WorkspaceLifecycleController.ACCESS_KEY_HEADER,
                    required = false
            ) String accessKey,
            @Valid @RequestBody ArchiveRequest request
    ) {
        return RoutineResponse.from(operationsUseCase.updateRoutineArchive(
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
            @RequestHeader(
                    name = WorkspaceLifecycleController.IDEMPOTENCY_KEY_HEADER,
                    required = false
            ) String idempotencyKey,
            @RequestHeader(
                    name = WorkspaceLifecycleController.ACCESS_KEY_HEADER,
                    required = false
            ) String accessKey,
            @Valid @RequestBody CreateSeasonRoundRequest request
    ) {
        WorkspaceContract.SeasonRoundResult result = operationsUseCase.createSeasonRound(
                teamId,
                seasonId,
                idempotencyKey,
                accessKey,
                new WorkspaceContract.CreateSeasonRoundCommand(request.name(), request.meetingDate())
        );
        return SeasonRoundResponse.from(result);
    }

    @PutMapping("/teams/{teamId}/seasons/{seasonId}/rounds/{roundId}")
    public SeasonRoundResponse updateSeasonRound(
            @PathVariable UUID teamId,
            @PathVariable UUID seasonId,
            @PathVariable UUID roundId,
            @RequestHeader(
                    name = WorkspaceLifecycleController.ACCESS_KEY_HEADER,
                    required = false
            ) String accessKey,
            @Valid @RequestBody UpdateSeasonRoundRequest request
    ) {
        return SeasonRoundResponse.from(operationsUseCase.updateSeasonRound(
                teamId,
                seasonId,
                roundId,
                accessKey,
                new WorkspaceContract.UpdateSeasonRoundCommand(request.name(), request.meetingDate())
        ));
    }

    @PatchMapping("/teams/{teamId}/seasons/{seasonId}/rounds/{roundId}/archive")
    public SeasonRoundResponse updateSeasonRoundArchive(
            @PathVariable UUID teamId,
            @PathVariable UUID seasonId,
            @PathVariable UUID roundId,
            @RequestHeader(
                    name = WorkspaceLifecycleController.ACCESS_KEY_HEADER,
                    required = false
            ) String accessKey,
            @Valid @RequestBody ArchiveRequest request
    ) {
        return SeasonRoundResponse.from(operationsUseCase.updateSeasonRoundArchive(
                teamId,
                seasonId,
                roundId,
                accessKey,
                request.archived()
        ));
    }

    @PatchMapping(
            "/teams/{teamId}/seasons/{seasonId}/rounds/{roundId}"
                    + "/routine-executions/{executionId}/completion"
    )
    public RoutineExecutionResponse updateRoutineExecutionCompletion(
            @PathVariable UUID teamId,
            @PathVariable UUID seasonId,
            @PathVariable UUID roundId,
            @PathVariable UUID executionId,
            @RequestHeader(
                    name = WorkspaceLifecycleController.ACCESS_KEY_HEADER,
                    required = false
            ) String accessKey,
            @Valid @RequestBody UpdateRoutineExecutionCompletionRequest request
    ) {
        return RoutineExecutionResponse.from(operationsUseCase.updateRoutineExecutionCompletion(
                teamId,
                seasonId,
                roundId,
                executionId,
                accessKey,
                request.completed()
        ));
    }
}
