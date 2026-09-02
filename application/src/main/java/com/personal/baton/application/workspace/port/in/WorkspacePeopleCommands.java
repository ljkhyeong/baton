package com.personal.baton.application.workspace.port.in;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class WorkspacePeopleCommands {

    private WorkspacePeopleCommands() {
    }

    public record CreateMemberCommand(String name) {
    }

    public record UpdateMemberCommand(String name) {
    }

    public record CreateRoleCommand(
            String name,
            String purpose,
            UUID currentMemberId,
            UUID nextMemberId,
            LocalDate assignmentStartDate,
            LocalDate assignmentEndDate,
            List<String> responsibilities,
            String risk
    ) {
    }

    public record UpdateRoleCommand(
            String name,
            String purpose,
            UUID currentMemberId,
            UUID nextMemberId,
            LocalDate assignmentStartDate,
            LocalDate assignmentEndDate,
            List<String> responsibilities,
            String risk
    ) {
    }

    public record PrepareRoleHandoffCommand(
            UUID toMemberId,
            LocalDate incomingAssignmentStartDate,
            LocalDate incomingAssignmentEndDate
    ) {
    }

    public record TransferRoleHandoffCommand(
            UUID confirmedByMemberId,
            boolean warningAcknowledged
    ) {
    }

    public record ConfirmRoleHandoffCommand(UUID confirmedByMemberId) {
    }
}
