package com.personal.baton.application.workspace;

import org.springframework.stereotype.Component;
import com.personal.baton.application.workspace.port.out.WorkspaceOperationsRepository;
import com.personal.baton.domain.workspace.DomainValidationException;
import com.personal.baton.domain.workspace.RoundSchedule;
import com.personal.baton.domain.workspace.Routine;
import com.personal.baton.domain.workspace.Season;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

@Component
final class WorkspaceRoundSchedulePolicy {

    private final WorkspaceOperationsRepository repository;

    WorkspaceRoundSchedulePolicy(WorkspaceOperationsRepository repository) {
        this.repository = repository;
    }

    void requireDeadlineRulesForScheduleActivation(UUID seasonId) {
        List<Routine> routines = repository.findRoutinesBySeasonId(seasonId);
        for (Routine routine : routines) {
            if (routine.getArchivedAt() != null) {
                continue;
            }
            if (routine.getDeadlineDayOffset() == null || routine.getDeadlineTime() == null) {
                throw new DomainValidationException(
                        "자동 회차를 사용하려면 모든 루틴에 실제 마감 규칙이 필요합니다"
                );
            }
        }
    }

    void requireDeadlineRuleForEnabledSchedule(
            Season season,
            Integer deadlineDayOffset,
            LocalTime deadlineTime
    ) {
        RoundSchedule schedule = season.getRoundSchedule();
        if (schedule != null
                && schedule.isEnabled()
                && (deadlineDayOffset == null || deadlineTime == null)) {
            throw new DomainValidationException(
                    "자동 회차를 사용하는 동안 루틴의 실제 마감 규칙을 제거할 수 없습니다"
            );
        }
    }
}
