package com.personal.baton.application.workspace.port.in;

import com.personal.baton.domain.workspace.RoutinePhase;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

public final class WorkspaceOperationsCommands {

    private WorkspaceOperationsCommands() {
    }

    public record CreateRoutineCommand(
            String title,
            RoutinePhase phase,
            String dueLabel,
            UUID ownerRoleId,
            String detail,
            Integer deadlineDayOffset,
            LocalTime deadlineTime
    ) {
    }

    public record UpdateRoutineCommand(
            String title,
            RoutinePhase phase,
            String dueLabel,
            UUID ownerRoleId,
            String detail,
            Integer deadlineDayOffset,
            LocalTime deadlineTime
    ) {
    }

    public record CreateSeasonRoundCommand(String name, LocalDate meetingDate) {
    }

    public record UpdateSeasonRoundCommand(String name, LocalDate meetingDate) {
    }
}
