package com.personal.baton.application.workspace.port.in;

import com.personal.baton.domain.workspace.RoundRecurrence;
import com.personal.baton.domain.workspace.WorkspaceTemplate;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

public final class WorkspaceLifecycleCommands {

    private WorkspaceLifecycleCommands() {
    }

    public record CreateWorkspaceCommand(
            String teamName,
            String seasonName,
            LocalDate startDate,
            LocalDate endDate,
            List<String> memberNames,
            WorkspaceTemplate template
    ) {
        public CreateWorkspaceCommand(String teamName, String seasonName, LocalDate startDate, LocalDate endDate,
                List<String> memberNames) { this(teamName, seasonName, startDate, endDate, memberNames, null); }
    }

    public record UpdateSeasonCommand(String name, LocalDate startDate, LocalDate endDate) {
    }

    public record UpdateRoundScheduleCommand(
            String timeZone,
            LocalDate firstMeetingDate,
            LocalTime meetingTime,
            RoundRecurrence recurrence,
            int generationLeadDays,
            boolean enabled
    ) {
    }

    public record CreateNextSeasonCommand(
            String name,
            LocalDate startDate,
            LocalDate endDate,
            List<UUID> roleIds,
            List<UUID> routineIds
    ) {
    }
}
