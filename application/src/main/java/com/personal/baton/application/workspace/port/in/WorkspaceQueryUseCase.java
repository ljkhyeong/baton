package com.personal.baton.application.workspace.port.in;

import com.personal.baton.application.workspace.port.in.WorkspaceDecisionUseCase.DecisionResult;
import com.personal.baton.application.workspace.port.in.WorkspaceHandoffItemUseCase.HandoffItemResult;
import com.personal.baton.application.workspace.port.in.WorkspaceMemberUseCase.MemberResult;
import com.personal.baton.application.workspace.port.in.WorkspaceRoleHandoffUseCase.RoleHandoffResult;
import com.personal.baton.application.workspace.port.in.WorkspaceRoleResourceQueryUseCase.RoleResourceResult;
import com.personal.baton.application.workspace.port.in.WorkspaceRoleUseCase.RoleResult;
import com.personal.baton.application.workspace.port.in.WorkspaceRoutineUseCase.RoutineResult;
import com.personal.baton.application.workspace.port.in.WorkspaceSeasonRoundUseCase.SeasonRoundResult;
import com.personal.baton.application.workspace.port.in.WorkspaceSeasonUseCase.SeasonResult;
import com.personal.baton.application.workspace.port.in.WorkspaceSeasonUseCase.RoundScheduleResult;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

public interface WorkspaceQueryUseCase {

    WorkspaceResult getWorkspaceAuthorized(
            UUID teamId,
            UUID seasonId,
            WorkspaceAuthorization authorization
    );

    @Transactional(readOnly = true)
    default WorkspaceResult getWorkspace(UUID teamId, UUID seasonId, String accessKey) {
        return getWorkspaceAuthorized(teamId, seasonId, legacy(accessKey));
    }

    private static WorkspaceAuthorization legacy(String accessKey) {
        return new WorkspaceAuthorization.LegacyAccessKey(accessKey);
    }

    record WorkspaceResult(
            TeamResult team,
            SeasonResult season,
            List<SeasonSummaryResult> seasons,
            List<MemberResult> members,
            List<RoleResult> roles,
            List<RoutineResult> routines,
            List<SeasonRoundResult> rounds,
            List<DecisionResult> decisions,
            List<HandoffItemResult> handoffItems,
            List<RoleResourceResult> resources,
            List<RoleHandoffResult> roleHandoffs,
            List<ContinuitySignalResult> continuitySignals
    ) {
        public WorkspaceResult(
                TeamResult team,
                SeasonResult season,
                List<SeasonSummaryResult> seasons,
                List<MemberResult> members,
                List<RoleResult> roles,
                List<RoutineResult> routines,
                List<SeasonRoundResult> rounds,
                List<DecisionResult> decisions,
                List<HandoffItemResult> handoffItems,
                List<RoleResourceResult> resources
        ) {
            this(
                    team,
                    season,
                    seasons,
                    members,
                    roles,
                    routines,
                    rounds,
                    decisions,
                    handoffItems,
                    resources,
                    List.of(),
                    List.of()
            );
        }

        public WorkspaceResult(
                TeamResult team,
                SeasonResult season,
                List<SeasonSummaryResult> seasons,
                List<MemberResult> members,
                List<RoleResult> roles,
                List<RoutineResult> routines,
                List<SeasonRoundResult> rounds,
                List<DecisionResult> decisions,
                List<HandoffItemResult> handoffItems,
                List<RoleResourceResult> resources,
                List<RoleHandoffResult> roleHandoffs
        ) {
            this(
                    team,
                    season,
                    seasons,
                    members,
                    roles,
                    routines,
                    rounds,
                    decisions,
                    handoffItems,
                    resources,
                    roleHandoffs,
                    List.of()
            );
        }
    }

    record TeamResult(UUID id, String name) {
    }

    record SeasonSummaryResult(
            UUID id,
            String name,
            LocalDate startDate,
            LocalDate endDate,
            Instant endedAt,
            UUID previousSeasonId,
            String timeZone,
            RoundScheduleResult roundSchedule
    ) {
        public SeasonSummaryResult(
                UUID id,
                String name,
                LocalDate startDate,
                LocalDate endDate,
                Instant endedAt,
                UUID previousSeasonId
        ) {
            this(
                    id,
                    name,
                    startDate,
                    endDate,
                    endedAt,
                    previousSeasonId,
                    "Asia/Seoul",
                    null
            );
        }
    }

    record ContinuitySignalResult(
            ContinuitySignalType type,
            ContinuitySignalSeverity severity,
            UUID roleId,
            UUID routineId,
            String title,
            String reason,
            String recommendedAction,
            LocalDate relevantDate
    ) {
    }
}
