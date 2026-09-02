package com.personal.baton.application.workspace;

import com.personal.baton.application.workspace.port.in.ContinuitySignalSeverity;
import com.personal.baton.application.workspace.port.in.ContinuitySignalType;
import com.personal.baton.application.workspace.port.in.WorkspaceContract.ContinuitySignalResult;
import com.personal.baton.domain.workspace.HandoffItem;
import com.personal.baton.domain.workspace.Member;
import com.personal.baton.domain.workspace.Role;
import com.personal.baton.domain.workspace.RoleHandoff;
import com.personal.baton.domain.workspace.RoleResource;
import com.personal.baton.domain.workspace.Routine;
import com.personal.baton.domain.workspace.RoutineExecution;
import com.personal.baton.domain.workspace.Season;
import com.personal.baton.domain.workspace.SeasonRound;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
final class ContinuitySignalAnalyzer {

    private final RoleContinuitySignalAnalyzer roleAnalyzer = new RoleContinuitySignalAnalyzer();
    private final HandoffContinuitySignalAnalyzer handoffAnalyzer =
            new HandoffContinuitySignalAnalyzer();
    private final RoutineContinuitySignalAnalyzer routineAnalyzer =
            new RoutineContinuitySignalAnalyzer();

    List<ContinuitySignalResult> analyze(
            Clock clock,
            Season season,
            List<Member> members,
            List<Role> roles,
            List<Routine> routines,
            List<SeasonRound> rounds,
            List<RoutineExecution> executions,
            List<HandoffItem> handoffItems,
            List<RoleResource> resources,
            List<RoleHandoff> roleHandoffs
    ) {
        if (season.isEnded()) {
            return List.of();
        }

        Instant now = clock.instant();
        Clock snapshotClock = Clock.fixed(now, ZoneOffset.UTC);
        ZoneId zoneId = season.getZoneId();
        LocalDate today = now.atZone(zoneId).toLocalDate();
        HandoffContinuitySignalAnalyzer.Analysis handoffAnalysis = handoffAnalyzer.analyze(
                roles,
                handoffItems,
                roleHandoffs,
                members,
                today
        );

        List<ContinuitySignalResult> signals = new ArrayList<>(handoffAnalysis.signals());
        signals.addAll(roleAnalyzer.analyze(
                season.getStartDate(),
                members,
                roles,
                handoffItems,
                resources,
                handoffAnalysis.signaledRoleIds(),
                today
        ));
        signals.addAll(routineAnalyzer.analyze(
                routines,
                rounds,
                executions,
                snapshotClock,
                zoneId
        ));
        return signals.stream()
                .sorted(continuitySignalOrder())
                .toList();
    }

    private Comparator<ContinuitySignalResult> continuitySignalOrder() {
        return Comparator
                .comparingInt((ContinuitySignalResult signal) ->
                        signal.severity() == ContinuitySignalSeverity.CRITICAL ? 0 : 1)
                .thenComparing(
                        ContinuitySignalResult::relevantDate,
                        Comparator.nullsLast(Comparator.naturalOrder())
                )
                .thenComparingInt(signal -> typePriority(signal.type()))
                .thenComparing(ContinuitySignalResult::title)
                .thenComparing(ContinuitySignalResult::roleId)
                .thenComparing(
                        ContinuitySignalResult::routineId,
                        Comparator.nullsLast(Comparator.naturalOrder())
                );
    }

    private int typePriority(ContinuitySignalType type) {
        return switch (type) {
            case ROLE_UNASSIGNED -> 0;
            case ROLE_SUCCESSOR_MISSING -> 1;
            case HANDOFF_INCOMPLETE -> 2;
            case ROUTINE_REPEATEDLY_OVERDUE -> 3;
            case ROLE_PREPARATION_INCOMPLETE -> 4;
        };
    }
}
