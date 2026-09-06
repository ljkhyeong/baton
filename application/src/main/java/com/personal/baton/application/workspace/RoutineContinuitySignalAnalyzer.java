package com.personal.baton.application.workspace;

import com.personal.baton.application.workspace.port.in.ContinuitySignalSeverity;
import com.personal.baton.application.workspace.port.in.ContinuitySignalType;
import com.personal.baton.application.workspace.port.in.WorkspaceContract.ContinuitySignalResult;
import com.personal.baton.domain.workspace.Routine;
import com.personal.baton.domain.workspace.RoutineExecution;
import com.personal.baton.domain.workspace.RoutineTimingStatus;
import com.personal.baton.domain.workspace.SeasonRound;
import java.time.Clock;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class RoutineContinuitySignalAnalyzer {

    private static final int REPEATED_OVERDUE_ROUNDS = 2;

    List<ContinuitySignalResult> analyze(
            List<Routine> routines,
            List<SeasonRound> rounds,
            List<RoutineExecution> executions,
            Clock clock,
            ZoneId zoneId
    ) {
        Set<UUID> activeRoundIds = new HashSet<>();
        for (SeasonRound round : rounds) {
            if (round.getArchivedAt() == null) {
                activeRoundIds.add(round.getId());
            }
        }

        Map<UUID, Set<UUID>> overdueRoundIdsByRoutine = new HashMap<>();
        for (RoutineExecution execution : executions) {
            if (activeRoundIds.contains(execution.getSeasonRoundId())
                    && execution.timingStatus(clock, zoneId) == RoutineTimingStatus.OVERDUE) {
                overdueRoundIdsByRoutine
                        .computeIfAbsent(execution.getRoutineId(), ignored -> new HashSet<>())
                        .add(execution.getSeasonRoundId());
            }
        }

        List<ContinuitySignalResult> signals = new ArrayList<>();
        for (Routine routine : routines) {
            if (routine.getArchivedAt() != null) {
                continue;
            }
            int overdueRoundCount = overdueRoundIdsByRoutine
                    .getOrDefault(routine.getId(), Set.of())
                    .size();
            if (overdueRoundCount < REPEATED_OVERDUE_ROUNDS) {
                continue;
            }
            signals.add(new ContinuitySignalResult(
                    ContinuitySignalType.ROUTINE_REPEATEDLY_OVERDUE,
                    overdueRoundCount >= 3
                            ? ContinuitySignalSeverity.CRITICAL
                            : ContinuitySignalSeverity.WARNING,
                    routine.getOwnerRoleId(),
                    routine.getId(),
                    routine.getTitle() + " 여러 회차에서 마감 지남",
                    routine.getTitle() + " 반복 업무가 서로 다른 " + overdueRoundCount
                            + "개 회차에서 마감 뒤에도 완료되지 않았습니다.",
                    "담당자와 마감을 확인하고 밀린 업무를 처리하세요.",
                    null
            ));
        }
        return signals;
    }
}
