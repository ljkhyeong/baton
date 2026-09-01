package com.personal.baton.adapter.in.web.workspace;

import com.personal.baton.adapter.in.web.workspace.WorkspaceRequests.CorrectSeasonNameRequest;
import com.personal.baton.adapter.in.web.workspace.WorkspaceRequests.CreateNextSeasonRequest;
import com.personal.baton.adapter.in.web.workspace.WorkspaceRequests.CreateWorkspaceRequest;
import com.personal.baton.adapter.in.web.workspace.WorkspaceRequests.UpdateRoundScheduleRequest;
import com.personal.baton.adapter.in.web.workspace.WorkspaceRequests.UpdateSeasonEndingRequest;
import com.personal.baton.adapter.in.web.workspace.WorkspaceRequests.UpdateSeasonRequest;
import com.personal.baton.adapter.in.web.workspace.WorkspaceResponses.AccessKeyResponse;
import com.personal.baton.adapter.in.web.workspace.WorkspaceResponses.CreateWorkspaceResponse;
import com.personal.baton.adapter.in.web.workspace.WorkspaceResponses.NextSeasonResponse;
import com.personal.baton.adapter.in.web.workspace.WorkspaceResponses.SeasonResponse;
import com.personal.baton.adapter.in.web.workspace.WorkspaceResponses.WorkspaceResponse;
import com.personal.baton.application.workspace.port.in.WorkspaceContract;
import com.personal.baton.application.workspace.port.in.WorkspaceLifecycleUseCase;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

@RestController
@RequestMapping("/api/v1")
public class WorkspaceLifecycleController {

    static final String ACCESS_KEY_HEADER = "X-Baton-Access-Key";
    static final String CREATION_KEY_HEADER = "X-Baton-Creation-Key";
    static final String RECOVERY_KEY_HEADER = "X-Baton-Recovery-Key";
    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final WorkspaceLifecycleUseCase lifecycleUseCase;

    public WorkspaceLifecycleController(WorkspaceLifecycleUseCase lifecycleUseCase) {
        this.lifecycleUseCase = lifecycleUseCase;
    }

    @PostMapping("/workspaces")
    public ResponseEntity<CreateWorkspaceResponse> createWorkspace(
            @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestHeader(name = CREATION_KEY_HEADER, required = false) String creationKey,
            @Valid @RequestBody CreateWorkspaceRequest request
    ) {
        WorkspaceContract.CreatedWorkspaceResult result = lifecycleUseCase.createWorkspace(
                idempotencyKey,
                creationKey,
                new WorkspaceContract.CreateWorkspaceCommand(
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
                lifecycleUseCase.getWorkspace(teamId, seasonId, accessKey)
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
        return SeasonResponse.from(lifecycleUseCase.updateSeason(
                teamId,
                seasonId,
                accessKey,
                new WorkspaceContract.UpdateSeasonCommand(
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
        return SeasonResponse.from(lifecycleUseCase.updateRoundSchedule(
                teamId,
                seasonId,
                accessKey,
                new WorkspaceContract.UpdateRoundScheduleCommand(
                        request.timeZone(),
                        request.firstMeetingDate(),
                        request.meetingTime(),
                        request.recurrence(),
                        request.generationLeadDays(),
                        request.enabled()
                )
        ));
    }

    @PatchMapping("/teams/{teamId}/seasons/{seasonId}/name")
    public SeasonResponse correctSeasonName(
            @PathVariable UUID teamId,
            @PathVariable UUID seasonId,
            @RequestHeader(name = RECOVERY_KEY_HEADER, required = false) String recoveryKey,
            @Valid @RequestBody CorrectSeasonNameRequest request
    ) {
        return SeasonResponse.from(lifecycleUseCase.correctSeasonName(
                teamId, seasonId, recoveryKey, request.name()
        ));
    }

    @PatchMapping("/teams/{teamId}/seasons/{seasonId}/ending")
    public SeasonResponse updateSeasonEnding(
            @PathVariable UUID teamId,
            @PathVariable UUID seasonId,
            @RequestHeader(name = ACCESS_KEY_HEADER, required = false) String accessKey,
            @Valid @RequestBody UpdateSeasonEndingRequest request
    ) {
        return SeasonResponse.from(lifecycleUseCase.updateSeasonEnding(
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
        WorkspaceContract.NextSeasonResult result = lifecycleUseCase.createNextSeason(
                teamId,
                seasonId,
                idempotencyKey,
                accessKey,
                new WorkspaceContract.CreateNextSeasonCommand(
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

    @PostMapping("/teams/{teamId}/seasons/{seasonId}/access-key/rotate")
    public ResponseEntity<AccessKeyResponse> rotateAccessKey(
            @PathVariable UUID teamId,
            @PathVariable UUID seasonId,
            @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestHeader(name = ACCESS_KEY_HEADER, required = false) String accessKey
    ) {
        WorkspaceContract.AccessKeyResult result = lifecycleUseCase.rotateAccessKey(
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
        WorkspaceContract.AccessKeyResult result = lifecycleUseCase.recoverAccessKey(
                teamId,
                seasonId,
                idempotencyKey,
                recoveryKey
        );
        return noStoreAccessKey(result);
    }

    private ResponseEntity<AccessKeyResponse> noStoreAccessKey(WorkspaceContract.AccessKeyResult result) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(AccessKeyResponse.from(result));
    }

    private URI workspaceLocation(UUID teamId, UUID seasonId) {
        return UriComponentsBuilder
                .fromPath("/api/v1/teams/{teamId}/seasons/{seasonId}/workspace")
                .build(teamId, seasonId);
    }
}
