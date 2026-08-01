package com.personal.baton.application.workspace.port.in;

import com.personal.baton.domain.workspace.RoutinePhase;
import java.time.LocalTime;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

public interface WorkspaceRoutineUseCase {

    RoutineResult createRoutineAuthorized(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            WorkspaceAuthorization authorization,
            CreateRoutineCommand command
    );

    @Transactional
    default RoutineResult createRoutine(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            String accessKey,
            CreateRoutineCommand command
    ) {
        return createRoutineAuthorized(
                teamId,
                seasonId,
                idempotencyKey,
                legacy(accessKey),
                command
        );
    }

    RoutineResult updateRoutineAuthorized(
            UUID teamId,
            UUID seasonId,
            UUID routineId,
            WorkspaceAuthorization authorization,
            UpdateRoutineCommand command
    );

    @Transactional
    default RoutineResult updateRoutine(
            UUID teamId,
            UUID seasonId,
            UUID routineId,
            String accessKey,
            UpdateRoutineCommand command
    ) {
        return updateRoutineAuthorized(
                teamId,
                seasonId,
                routineId,
                legacy(accessKey),
                command
        );
    }

    private static WorkspaceAuthorization legacy(String accessKey) {
        return new WorkspaceAuthorization.LegacyAccessKey(accessKey);
    }

    record CreateRoutineCommand(
            String title,
            RoutinePhase phase,
            String dueLabel,
            UUID ownerRoleId,
            String detail,
            Integer deadlineDayOffset,
            LocalTime deadlineTime
    ) {
        public CreateRoutineCommand(
                String title,
                RoutinePhase phase,
                String dueLabel,
                UUID ownerRoleId,
                String detail
        ) {
            this(title, phase, dueLabel, ownerRoleId, detail, null, null);
        }
    }

    record UpdateRoutineCommand(
            String title,
            RoutinePhase phase,
            String dueLabel,
            UUID ownerRoleId,
            String detail,
            Integer deadlineDayOffset,
            LocalTime deadlineTime
    ) {
        public UpdateRoutineCommand(
                String title,
                RoutinePhase phase,
                String dueLabel,
                UUID ownerRoleId,
                String detail
        ) {
            this(title, phase, dueLabel, ownerRoleId, detail, null, null);
        }
    }

    record RoutineResult(
            UUID id,
            String title,
            RoutinePhase phase,
            String dueLabel,
            UUID ownerRoleId,
            String detail,
            Integer deadlineDayOffset,
            LocalTime deadlineTime
    ) {
        public RoutineResult(
                UUID id,
                String title,
                RoutinePhase phase,
                String dueLabel,
                UUID ownerRoleId,
                String detail
        ) {
            this(id, title, phase, dueLabel, ownerRoleId, detail, null, null);
        }
    }
}
