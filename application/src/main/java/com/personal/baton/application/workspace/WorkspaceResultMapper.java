package com.personal.baton.application.workspace;

import org.springframework.stereotype.Component;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.DecisionResult;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.HandoffItemResult;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.MemberResult;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.RoleHandoffResult;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.RoleHandoffTransitionResult;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.RoleResourceResult;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.RoleResult;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.RoundScheduleResult;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.RoutineExecutionResult;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.RoutineResult;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.SeasonResult;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.SeasonRoundResult;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.SeasonSummaryResult;
import com.personal.baton.domain.workspace.Decision;
import com.personal.baton.domain.workspace.HandoffItem;
import com.personal.baton.domain.workspace.Member;
import com.personal.baton.domain.workspace.Role;
import com.personal.baton.domain.workspace.RoleHandoff;
import com.personal.baton.domain.workspace.RoleResource;
import com.personal.baton.domain.workspace.RoundSchedule;
import com.personal.baton.domain.workspace.RoundTimingStatus;
import com.personal.baton.domain.workspace.Routine;
import com.personal.baton.domain.workspace.RoutineExecution;
import com.personal.baton.domain.workspace.RoutineTimingStatus;
import com.personal.baton.domain.workspace.Season;
import com.personal.baton.domain.workspace.SeasonRound;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
final class WorkspaceResultMapper {

    private static final String[] MEMBER_TONES = {
            "#d9e4da", "#f1d6cc", "#d8dfee", "#eee3bf", "#dce7ef", "#eadcf0"
    };

    private final Clock clock;

    WorkspaceResultMapper(Clock clock) {
        this.clock = clock;
    }

    SeasonResult toSeasonResult(Season season) {
        return new SeasonResult(
                season.getId(),
                season.getName(),
                season.getStartDate(),
                season.getEndDate(),
                season.getEndedAt(),
                season.getPreviousSeasonId(),
                season.getTimeZone(),
                toRoundScheduleResult(season)
        );
    }

    SeasonSummaryResult toSeasonSummaryResult(Season season) {
        return new SeasonSummaryResult(
                season.getId(),
                season.getName(),
                season.getStartDate(),
                season.getEndDate(),
                season.getEndedAt(),
                season.getPreviousSeasonId(),
                season.getTimeZone(),
                toRoundScheduleResult(season)
        );
    }

    MemberResult toMemberResult(Member member) {
        int codePoint = member.getName().codePointAt(0);
        String initials = new String(Character.toChars(codePoint));
        String tone = MEMBER_TONES[Math.floorMod(member.getId().hashCode(), MEMBER_TONES.length)];
        return new MemberResult(
                member.getId(),
                member.getName(),
                initials,
                tone,
                member.getDeactivatedAt()
        );
    }

    RoleResult toRoleResult(Role role) {
        return new RoleResult(
                role.getId(),
                role.getName(),
                role.getPurpose(),
                role.getCurrentMemberId(),
                role.getNextMemberId(),
                role.getAssignmentStartDate(),
                role.getAssignmentEndDate(),
                List.copyOf(role.getResponsibilities()),
                role.getRisk()
        );
    }

    RoleHandoffTransitionResult toRoleHandoffTransitionResult(
            Role role,
            RoleHandoff handoff
    ) {
        return new RoleHandoffTransitionResult(
                toRoleResult(role),
                toRoleHandoffResult(handoff)
        );
    }

    RoleHandoffResult toRoleHandoffResult(RoleHandoff handoff) {
        return new RoleHandoffResult(
                handoff.getId(),
                handoff.getRoleId(),
                handoff.getFromMemberId(),
                handoff.getToMemberId(),
                handoff.getOutgoingAssignmentStartDate(),
                handoff.getOutgoingAssignmentEndDate(),
                handoff.getIncomingAssignmentStartDate(),
                handoff.getIncomingAssignmentEndDate(),
                handoff.getStatus(),
                handoff.getPreparedAt(),
                handoff.getTransferredAt(),
                handoff.getAcceptedAt(),
                handoff.getCancelledAt(),
                handoff.getTransferredByMemberId(),
                handoff.getAcceptedByMemberId(),
                handoff.getCancelledByMemberId(),
                handoff.getSnapshotItemCount(),
                handoff.getSnapshotIncompleteItemCount(),
                handoff.getSnapshotResourceCount(),
                handoff.isWarningAcknowledged()
        );
    }

    RoutineResult toRoutineResult(Routine routine) {
        return new RoutineResult(
                routine.getId(),
                routine.getTitle(),
                routine.getPhase(),
                routine.getDueLabel(),
                routine.getOwnerRoleId(),
                routine.getDetail(),
                routine.getDeadlineDayOffset(),
                routine.getDeadlineTime(),
                routine.getArchivedAt()
        );
    }

    SeasonRoundResult toSeasonRoundResult(
            SeasonRound round,
            List<RoutineExecution> executions,
            Season season
    ) {
        return toSeasonRoundResult(round, executions, season, snapshotClock());
    }

    SeasonRoundResult toSeasonRoundResult(
            SeasonRound round,
            List<RoutineExecution> executions,
            Season season,
            Clock projectionClock
    ) {
        ZoneId zoneId = season.getZoneId();
        List<RoutineExecutionResult> executionResults = executions.stream()
                .map(execution -> toRoutineExecutionResult(execution, zoneId, projectionClock))
                .toList();
        return new SeasonRoundResult(
                round.getId(),
                round.getName(),
                round.getMeetingDate(),
                executionResults,
                round.getArchivedAt(),
                round.getOrigin(),
                round.getScheduledOccurrenceDate(),
                round.getScheduledAt(),
                roundTimingStatus(round, executionResults, projectionClock)
        );
    }

    RoutineExecutionResult toRoutineExecutionResult(
            RoutineExecution execution,
            ZoneId zoneId
    ) {
        return toRoutineExecutionResult(execution, zoneId, snapshotClock());
    }

    DecisionResult toDecisionResult(Decision decision, Map<UUID, Member> membersById) {
        Member author = membersById.get(decision.getAuthorMemberId());
        if (author == null) {
            throw new IllegalStateException("결정 작성자 구성원을 찾을 수 없습니다");
        }
        return new DecisionResult(
                decision.getId(),
                decision.getTitle(),
                decision.getReason(),
                decision.getAlternative(),
                decision.getCreatedAt(),
                decision.getAuthorMemberId(),
                author.getName(),
                List.copyOf(decision.getRoleIds()),
                decision.getArchivedAt()
        );
    }

    HandoffItemResult toHandoffItemResult(HandoffItem item) {
        return new HandoffItemResult(
                item.getId(),
                item.getRoleId(),
                item.getLabel(),
                item.getCategory(),
                item.isCompleted(),
                item.getCreatedAt(),
                item.getArchivedAt()
        );
    }

    RoleResourceResult toRoleResourceResult(RoleResource resource) {
        return new RoleResourceResult(
                resource.getId(),
                resource.getRoleId(),
                resource.getTitle(),
                resource.getUrl(),
                resource.getDescription(),
                resource.getCreatedAt(),
                resource.getArchivedAt()
        );
    }

    private RoundScheduleResult toRoundScheduleResult(Season season) {
        RoundSchedule schedule = season.getRoundSchedule();
        if (schedule == null) {
            return null;
        }
        return new RoundScheduleResult(
                season.getTimeZone(),
                schedule.getFirstMeetingDate(),
                schedule.getMeetingTime(),
                schedule.getRecurrence(),
                schedule.getGenerationLeadDays(),
                schedule.isEnabled(),
                schedule.getNextOccurrenceDate()
        );
    }

    private RoutineExecutionResult toRoutineExecutionResult(
            RoutineExecution execution,
            ZoneId zoneId,
            Clock projectionClock
    ) {
        return new RoutineExecutionResult(
                execution.getId(),
                execution.getSeasonRoundId(),
                execution.getRoutineId(),
                execution.getTitle(),
                execution.getPhase(),
                execution.getDueLabel(),
                execution.getOwnerRoleId(),
                execution.getStatus(),
                execution.getDetail(),
                execution.getDeadlineAt(),
                execution.timingStatus(projectionClock, zoneId)
        );
    }

    private RoundTimingStatus roundTimingStatus(
            SeasonRound round,
            List<RoutineExecutionResult> executions,
            Clock projectionClock
    ) {
        if (!executions.isEmpty()
                && executions.stream()
                .allMatch(execution -> execution.timingStatus() == RoutineTimingStatus.COMPLETED)) {
            return RoundTimingStatus.COMPLETED;
        }
        if (executions.stream()
                .anyMatch(execution -> execution.timingStatus() == RoutineTimingStatus.OVERDUE)) {
            return RoundTimingStatus.OVERDUE;
        }
        if (executions.stream().anyMatch(execution ->
                execution.timingStatus() == RoutineTimingStatus.IN_PROGRESS
                        || execution.timingStatus() == RoutineTimingStatus.COMPLETED)) {
            return RoundTimingStatus.IN_PROGRESS;
        }
        if (executions.isEmpty()
                && round.getScheduledAt() != null
                && !Instant.now(projectionClock).isBefore(round.getScheduledAt())) {
            return RoundTimingStatus.IN_PROGRESS;
        }
        return RoundTimingStatus.PLANNED;
    }

    private Clock snapshotClock() {
        return Clock.fixed(clock.instant(), clock.getZone());
    }
}
