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
import com.personal.baton.application.workspace.port.in.WorkspaceRoutineUseCase;
import com.personal.baton.application.workspace.port.in.WorkspaceSeasonRoundUseCase;
import jakarta.validation.Valid;
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
public class WorkspaceRoutineRoundController {

    private final WorkspaceRoutineUseCase routineUseCase;
    private final WorkspaceSeasonRoundUseCase seasonRoundUseCase;

    public WorkspaceRoutineRoundController(
            WorkspaceRoutineUseCase routineUseCase,
            WorkspaceSeasonRoundUseCase seasonRoundUseCase
    ) {
        this.routineUseCase = routineUseCase;
        this.seasonRoundUseCase = seasonRoundUseCase;
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
        var command = new WorkspaceRoutineUseCase.CreateRoutineCommand(
                request.title(),
                request.phase(),
                request.dueLabel(),
                request.ownerRoleId(),
                request.detail(),
                request.deadlineDayOffset(),
                request.deadlineTime()
        );
        WorkspaceRoutineUseCase.RoutineResult result = invokeAuthorized(
                accessKey,
                principal,
                key -> routineUseCase.createRoutine(
                        teamId,
                        seasonId,
                        idempotencyKey,
                        key,
                        command
                ),
                authorization -> routineUseCase.createRoutineAuthorized(
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
        var command = new WorkspaceRoutineUseCase.UpdateRoutineCommand(
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
                key -> routineUseCase.updateRoutine(
                        teamId,
                        seasonId,
                        routineId,
                        key,
                        command
                ),
                authorization -> routineUseCase.updateRoutineAuthorized(
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
        var command = new WorkspaceSeasonRoundUseCase.CreateSeasonRoundCommand(
                request.name(),
                request.meetingDate()
        );
        WorkspaceSeasonRoundUseCase.SeasonRoundResult result = invokeAuthorized(
                accessKey,
                principal,
                key -> seasonRoundUseCase.createSeasonRound(
                        teamId,
                        seasonId,
                        idempotencyKey,
                        key,
                        command
                ),
                authorization -> seasonRoundUseCase.createSeasonRoundAuthorized(
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
        var command = new WorkspaceSeasonRoundUseCase.UpdateSeasonRoundCommand(
                request.name(),
                request.meetingDate()
        );
        return SeasonRoundResponse.from(invokeAuthorized(
                accessKey,
                principal,
                key -> seasonRoundUseCase.updateSeasonRound(
                        teamId,
                        seasonId,
                        roundId,
                        key,
                        command
                ),
                authorization -> seasonRoundUseCase.updateSeasonRoundAuthorized(
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
                key -> seasonRoundUseCase.updateSeasonRoundArchive(
                        teamId,
                        seasonId,
                        roundId,
                        key,
                        request.archived()
                ),
                authorization -> seasonRoundUseCase.updateSeasonRoundArchiveAuthorized(
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
                key -> seasonRoundUseCase.updateRoutineExecutionCompletion(
                        teamId,
                        seasonId,
                        roundId,
                        executionId,
                        key,
                        request.completed()
                ),
                authorization -> seasonRoundUseCase.updateRoutineExecutionCompletionAuthorized(
                        teamId,
                        seasonId,
                        roundId,
                        executionId,
                        authorization,
                        request.completed()
                )
        ));
    }
}
