package com.personal.baton.adapter.in.web.workspace;

import com.personal.baton.adapter.in.web.workspace.WorkspaceRequests.CreateNextSeasonRequest;
import com.personal.baton.adapter.in.web.workspace.WorkspaceRequests.UpdateRoundScheduleRequest;
import com.personal.baton.adapter.in.web.workspace.WorkspaceRequests.UpdateSeasonEndingRequest;
import com.personal.baton.adapter.in.web.workspace.WorkspaceRequests.UpdateSeasonRequest;
import com.personal.baton.adapter.in.web.workspace.WorkspaceResponses.NextSeasonResponse;
import com.personal.baton.adapter.in.web.workspace.WorkspaceResponses.SeasonResponse;
import com.personal.baton.application.workspace.port.in.WorkspaceSeasonUseCase;
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
public class WorkspaceSeasonController {

    private final WorkspaceSeasonUseCase seasonUseCase;

    public WorkspaceSeasonController(WorkspaceSeasonUseCase seasonUseCase) {
        this.seasonUseCase = seasonUseCase;
    }

    @PutMapping("/teams/{teamId}/seasons/{seasonId}")
    public SeasonResponse updateSeason(
            @PathVariable UUID teamId,
            @PathVariable UUID seasonId,
            @RequestHeader(name = ACCESS_KEY_HEADER, required = false) String accessKey,
            Authentication principal,
            @Valid @RequestBody UpdateSeasonRequest request
    ) {
        var command = new WorkspaceSeasonUseCase.UpdateSeasonCommand(
                request.name(),
                request.startDate(),
                request.endDate()
        );
        WorkspaceSeasonUseCase.SeasonResult result = invokeAuthorized(
                accessKey,
                principal,
                key -> seasonUseCase.updateSeason(teamId, seasonId, key, command),
                authorization -> seasonUseCase.updateSeasonAuthorized(
                        teamId,
                        seasonId,
                        authorization,
                        command
                )
        );
        return SeasonResponse.from(result);
    }

    @PutMapping("/teams/{teamId}/seasons/{seasonId}/round-schedule")
    public SeasonResponse updateRoundSchedule(
            @PathVariable UUID teamId,
            @PathVariable UUID seasonId,
            @RequestHeader(name = ACCESS_KEY_HEADER, required = false) String accessKey,
            Authentication principal,
            @Valid @RequestBody UpdateRoundScheduleRequest request
    ) {
        var command = new WorkspaceSeasonUseCase.UpdateRoundScheduleCommand(
                request.timeZone(),
                request.firstMeetingDate(),
                request.meetingTime(),
                request.recurrence(),
                request.generationLeadDays(),
                request.enabled()
        );
        WorkspaceSeasonUseCase.SeasonResult result = invokeAuthorized(
                accessKey,
                principal,
                key -> seasonUseCase.updateRoundSchedule(teamId, seasonId, key, command),
                authorization -> seasonUseCase.updateRoundScheduleAuthorized(
                        teamId,
                        seasonId,
                        authorization,
                        command
                )
        );
        return SeasonResponse.from(result);
    }

    @PatchMapping("/teams/{teamId}/seasons/{seasonId}/ending")
    public SeasonResponse updateSeasonEnding(
            @PathVariable UUID teamId,
            @PathVariable UUID seasonId,
            @RequestHeader(name = ACCESS_KEY_HEADER, required = false) String accessKey,
            Authentication principal,
            @Valid @RequestBody UpdateSeasonEndingRequest request
    ) {
        WorkspaceSeasonUseCase.SeasonResult result = invokeAuthorized(
                accessKey,
                principal,
                key -> seasonUseCase.updateSeasonEnding(
                        teamId,
                        seasonId,
                        key,
                        request.ended()
                ),
                authorization -> seasonUseCase.updateSeasonEndingAuthorized(
                        teamId,
                        seasonId,
                        authorization,
                        request.ended()
                )
        );
        return SeasonResponse.from(result);
    }

    @PostMapping("/teams/{teamId}/seasons/{seasonId}/successor")
    public ResponseEntity<NextSeasonResponse> createNextSeason(
            @PathVariable UUID teamId,
            @PathVariable UUID seasonId,
            @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestHeader(name = ACCESS_KEY_HEADER, required = false) String accessKey,
            Authentication principal,
            @Valid @RequestBody CreateNextSeasonRequest request
    ) {
        var command = new WorkspaceSeasonUseCase.CreateNextSeasonCommand(
                request.name(),
                request.startDate(),
                request.endDate(),
                request.copyRoleIds(),
                request.copyRoutineIds()
        );
        WorkspaceSeasonUseCase.NextSeasonResult result = invokeAuthorized(
                accessKey,
                principal,
                key -> seasonUseCase.createNextSeason(
                        teamId,
                        seasonId,
                        idempotencyKey,
                        key,
                        command
                ),
                authorization -> seasonUseCase.createNextSeasonAuthorized(
                        teamId,
                        seasonId,
                        idempotencyKey,
                        authorization,
                        command
                )
        );
        URI location = URI.create("/api/v1/teams/" + teamId
                + "/seasons/" + result.season().id() + "/workspace");
        return ResponseEntity.created(location).body(NextSeasonResponse.from(result));
    }
}
