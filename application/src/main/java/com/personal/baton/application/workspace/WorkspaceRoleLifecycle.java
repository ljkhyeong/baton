package com.personal.baton.application.workspace;

import com.personal.baton.application.workspace.WorkspaceContentIdempotency.ContentCreationAttempt;
import com.personal.baton.application.workspace.error.RoleHandoffStateConflictException;
import com.personal.baton.application.workspace.error.RoleHandoffWarningConfirmationRequiredException;
import com.personal.baton.application.workspace.error.RoleNameConflictException;
import com.personal.baton.application.workspace.error.WorkspaceNotFoundException;
import com.personal.baton.application.workspace.port.in.WorkspaceRoleHandoffUseCase.ConfirmRoleHandoffCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceRoleHandoffUseCase.PrepareRoleHandoffCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceRoleHandoffUseCase.RoleHandoffTransitionResult;
import com.personal.baton.application.workspace.port.in.WorkspaceRoleHandoffUseCase.TransferRoleHandoffCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceRoleUseCase.CreateRoleCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceRoleUseCase.RoleResult;
import com.personal.baton.application.workspace.port.in.WorkspaceRoleUseCase.UpdateRoleCommand;
import com.personal.baton.application.workspace.port.out.WorkspaceRepository;
import com.personal.baton.domain.workspace.ContentCreationOperation;
import com.personal.baton.domain.workspace.DomainValidationException;
import com.personal.baton.domain.workspace.HandoffItem;
import com.personal.baton.domain.workspace.Role;
import com.personal.baton.domain.workspace.RoleHandoff;
import com.personal.baton.domain.workspace.RoleHandoffStatus;
import com.personal.baton.domain.workspace.Season;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

final class WorkspaceRoleLifecycle {

    private final WorkspaceRepository repository;
    private final Clock clock;
    private final WorkspaceResultMapper resultMapper;
    private final WorkspaceContentIdempotency contentIdempotency;
    private final WorkspaceMemberReferenceValidator memberReferenceValidator;

    WorkspaceRoleLifecycle(
            WorkspaceRepository repository,
            Clock clock,
            WorkspaceResultMapper resultMapper,
            WorkspaceContentIdempotency contentIdempotency,
            WorkspaceMemberReferenceValidator memberReferenceValidator
    ) {
        this.repository = repository;
        this.clock = clock;
        this.resultMapper = resultMapper;
        this.contentIdempotency = contentIdempotency;
        this.memberReferenceValidator = memberReferenceValidator;
    }

    RoleResult createRole(
            UUID teamId,
            UUID seasonId,
            WorkspaceScope scope,
            String idempotencyKey,
            CreateRoleCommand command
    ) {
        Role role = Role.create(
                UUID.randomUUID(),
                teamId,
                seasonId,
                command.name(),
                command.purpose(),
                command.currentMemberId(),
                command.nextMemberId(),
                command.assignmentStartDate(),
                command.assignmentEndDate(),
                command.responsibilities(),
                command.risk()
        );
        ContentCreationAttempt attempt = contentIdempotency.prepare(
                teamId,
                seasonId,
                ContentCreationOperation.ROLE,
                idempotencyKey,
                contentIdempotency.fingerprintRoleRequest(teamId, seasonId, role),
                role.getId()
        );
        if (attempt.replayResourceId() != null) {
            Role existing = repository.findRoleById(attempt.replayResourceId())
                    .filter(found -> found.getTeamId().equals(teamId))
                    .filter(found -> found.getSeasonId().equals(seasonId))
                    .orElseThrow(() ->
                            contentIdempotency.missingResource(ContentCreationOperation.ROLE));
            return resultMapper.toRoleResult(existing);
        }
        memberReferenceValidator.requireActive(
                teamId,
                role.getCurrentMemberId(),
                role.getNextMemberId()
        );
        validateRoleAssignmentDates(
                scope.season(),
                role.getAssignmentStartDate(),
                role.getAssignmentEndDate()
        );
        if (repository.existsRoleBySeasonIdAndName(seasonId, role.getName())) {
            throw new RoleNameConflictException();
        }
        contentIdempotency.reserve(attempt);
        return resultMapper.toRoleResult(repository.saveRole(role));
    }

    RoleResult updateRole(
            UUID teamId,
            UUID seasonId,
            UUID roleId,
            WorkspaceScope scope,
            UpdateRoleCommand command
    ) {
        Role role = requireRoleForUpdate(teamId, seasonId, roleId);
        repository.findOpenRoleHandoffByRoleIdWithSharedLock(roleId).ifPresent(handoff -> {
            if (handoff.getStatus() == RoleHandoffStatus.TRANSFERRED) {
                throw handoffStateConflict(
                        "전달된 바통의 역할 내용은 수락 또는 취소 전까지 바꿀 수 없습니다"
                );
            }
            if (!Objects.equals(role.getCurrentMemberId(), command.currentMemberId())
                    || !Objects.equals(role.getNextMemberId(), command.nextMemberId())
                    || !Objects.equals(role.getAssignmentStartDate(), command.assignmentStartDate())
                    || !Objects.equals(role.getAssignmentEndDate(), command.assignmentEndDate())) {
                throw handoffStateConflict(
                        "진행 중인 바통의 담당자와 담당 기간은 수락 또는 취소 전까지 바꿀 수 없습니다"
                );
            }
        });
        String normalizedName = Role.normalizeName(command.name());
        memberReferenceValidator.requireActive(
                teamId,
                Objects.equals(role.getCurrentMemberId(), command.currentMemberId())
                        ? null
                        : command.currentMemberId(),
                Objects.equals(role.getNextMemberId(), command.nextMemberId())
                        ? null
                        : command.nextMemberId()
        );
        validateRoleAssignmentDates(
                scope.season(),
                command.assignmentStartDate(),
                command.assignmentEndDate()
        );
        if (repository.existsRoleBySeasonIdAndNameAndIdNot(seasonId, normalizedName, roleId)) {
            throw new RoleNameConflictException();
        }
        role.update(
                normalizedName,
                command.purpose(),
                command.currentMemberId(),
                command.nextMemberId(),
                command.assignmentStartDate(),
                command.assignmentEndDate(),
                command.responsibilities(),
                command.risk()
        );
        return resultMapper.toRoleResult(repository.saveRole(role));
    }

    RoleHandoffTransitionResult prepareRoleHandoff(
            UUID teamId,
            UUID seasonId,
            UUID roleId,
            WorkspaceScope scope,
            String idempotencyKey,
            PrepareRoleHandoffCommand command
    ) {
        Role role = requireRoleForUpdate(teamId, seasonId, roleId);
        UUID handoffId = UUID.randomUUID();
        ContentCreationAttempt attempt = contentIdempotency.prepare(
                teamId,
                seasonId,
                ContentCreationOperation.ROLE_HANDOFF,
                idempotencyKey,
                contentIdempotency.fingerprintRoleHandoffRequest(
                        teamId,
                        seasonId,
                        roleId,
                        command
                ),
                handoffId
        );
        if (attempt.replayResourceId() != null) {
            RoleHandoff existing = repository.findRoleHandoffById(attempt.replayResourceId())
                    .filter(handoff -> handoff.getTeamId().equals(teamId))
                    .filter(handoff -> handoff.getSeasonId().equals(seasonId))
                    .filter(handoff -> handoff.getRoleId().equals(roleId))
                    .orElseThrow(() -> contentIdempotency.missingResource(
                            ContentCreationOperation.ROLE_HANDOFF
                    ));
            return resultMapper.toRoleHandoffTransitionResult(role, existing);
        }
        if (repository.findOpenRoleHandoffByRoleIdWithSharedLock(roleId).isPresent()) {
            throw handoffStateConflict("이 역할에는 이미 진행 중인 바통이 있습니다");
        }
        UUID fromMemberId = role.getCurrentMemberId();
        if (fromMemberId == null) {
            throw handoffStateConflict("현재 담당자를 지정한 뒤 바통 준비를 시작해 주세요");
        }
        if (role.getAssignmentStartDate() == null) {
            throw handoffStateConflict("현재 담당 시작일을 지정한 뒤 바통 준비를 시작해 주세요");
        }
        if (Objects.equals(fromMemberId, command.toMemberId())) {
            throw handoffStateConflict("현재 담당자와 다음 담당자는 달라야 합니다");
        }
        if (role.getNextMemberId() != null
                && !Objects.equals(role.getNextMemberId(), command.toMemberId())) {
            throw handoffStateConflict("역할에 지정된 다음 담당자와 바통 대상이 다릅니다");
        }
        memberReferenceValidator.requireActive(teamId, fromMemberId, command.toMemberId());
        validateRoleAssignmentDates(
                scope.season(),
                command.incomingAssignmentStartDate(),
                command.incomingAssignmentEndDate()
        );

        Instant preparedAt = Instant.now(clock);
        role.prepareHandoff(command.toMemberId());
        RoleHandoff handoff = RoleHandoff.prepare(
                handoffId,
                teamId,
                seasonId,
                roleId,
                fromMemberId,
                command.toMemberId(),
                role.getAssignmentStartDate(),
                role.getAssignmentEndDate(),
                command.incomingAssignmentStartDate(),
                command.incomingAssignmentEndDate(),
                preparedAt
        );
        contentIdempotency.reserve(attempt);
        Role savedRole = repository.saveRole(role);
        RoleHandoff savedHandoff = repository.saveRoleHandoff(handoff);
        return resultMapper.toRoleHandoffTransitionResult(savedRole, savedHandoff);
    }

    RoleHandoffTransitionResult transferRoleHandoff(
            UUID teamId,
            UUID seasonId,
            UUID roleId,
            UUID handoffId,
            TransferRoleHandoffCommand command
    ) {
        Role role = requireRoleForUpdate(teamId, seasonId, roleId);
        RoleHandoff handoff = requireRoleHandoffForUpdate(
                teamId,
                seasonId,
                roleId,
                handoffId
        );
        if (handoff.getStatus() == RoleHandoffStatus.ACCEPTED
                && Objects.equals(
                handoff.getTransferredByMemberId(),
                command.confirmedByMemberId()
        )) {
            return resultMapper.toRoleHandoffTransitionResult(role, handoff);
        }
        if (handoff.getStatus() == RoleHandoffStatus.TRANSFERRED
                && Objects.equals(
                handoff.getTransferredByMemberId(),
                command.confirmedByMemberId()
        )) {
            return resultMapper.toRoleHandoffTransitionResult(role, handoff);
        }
        requireHandoffStatus(
                handoff,
                RoleHandoffStatus.PREPARING,
                "준비 중인 바통만 전달할 수 있습니다"
        );
        requireDeclaredConfirmer(
                handoff.getFromMemberId(),
                command.confirmedByMemberId(),
                "현재 담당자 명의로 전달을 확인해 주세요"
        );
        requireRoleMatchesHandoff(role, handoff);
        memberReferenceValidator.requireActive(
                teamId,
                handoff.getFromMemberId(),
                handoff.getToMemberId()
        );

        List<HandoffItem> activeItems = repository.findHandoffItemsByRoleIds(List.of(roleId))
                .stream()
                .filter(item -> item.getArchivedAt() == null)
                .toList();
        int incompleteItemCount = (int) activeItems.stream()
                .filter(item -> !item.isCompleted())
                .count();
        int resourceCount = repository.findRoleResourcesByRoleIds(List.of(roleId)).size();
        boolean hasWarning = activeItems.isEmpty()
                || incompleteItemCount > 0
                || resourceCount == 0;
        if (hasWarning && !command.warningAcknowledged()) {
            throw new RoleHandoffWarningConfirmationRequiredException();
        }
        handoff.transfer(
                command.confirmedByMemberId(),
                Instant.now(clock),
                activeItems.size(),
                incompleteItemCount,
                resourceCount,
                command.warningAcknowledged()
        );
        return resultMapper.toRoleHandoffTransitionResult(
                role,
                repository.saveRoleHandoff(handoff)
        );
    }

    RoleHandoffTransitionResult acceptRoleHandoff(
            UUID teamId,
            UUID seasonId,
            UUID roleId,
            UUID handoffId,
            WorkspaceScope scope,
            ConfirmRoleHandoffCommand command
    ) {
        Role role = requireRoleForUpdate(teamId, seasonId, roleId);
        RoleHandoff handoff = requireRoleHandoffForUpdate(
                teamId,
                seasonId,
                roleId,
                handoffId
        );
        if (handoff.getStatus() == RoleHandoffStatus.ACCEPTED
                && Objects.equals(
                handoff.getAcceptedByMemberId(),
                command.confirmedByMemberId()
        )) {
            return resultMapper.toRoleHandoffTransitionResult(role, handoff);
        }
        requireHandoffStatus(
                handoff,
                RoleHandoffStatus.TRANSFERRED,
                "전달된 바통만 수락할 수 있습니다"
        );
        requireDeclaredConfirmer(
                handoff.getToMemberId(),
                command.confirmedByMemberId(),
                "다음 담당자 명의로 수락을 확인해 주세요"
        );
        requireRoleMatchesHandoff(role, handoff);
        memberReferenceValidator.requireActive(teamId, handoff.getToMemberId());
        validateRoleAssignmentDates(
                scope.season(),
                handoff.getIncomingAssignmentStartDate(),
                handoff.getIncomingAssignmentEndDate()
        );

        Instant acceptedAt = Instant.now(clock);
        handoff.accept(command.confirmedByMemberId(), acceptedAt);
        role.acceptHandoff(
                handoff.getFromMemberId(),
                handoff.getToMemberId(),
                handoff.getIncomingAssignmentStartDate(),
                handoff.getIncomingAssignmentEndDate()
        );
        Role savedRole = repository.saveRole(role);
        RoleHandoff savedHandoff = repository.saveRoleHandoff(handoff);
        return resultMapper.toRoleHandoffTransitionResult(savedRole, savedHandoff);
    }

    RoleHandoffTransitionResult cancelRoleHandoff(
            UUID teamId,
            UUID seasonId,
            UUID roleId,
            UUID handoffId,
            ConfirmRoleHandoffCommand command
    ) {
        Role role = requireRoleForUpdate(teamId, seasonId, roleId);
        RoleHandoff handoff = requireRoleHandoffForUpdate(
                teamId,
                seasonId,
                roleId,
                handoffId
        );
        if (handoff.getStatus() == RoleHandoffStatus.CANCELLED
                && Objects.equals(
                handoff.getFromMemberId(),
                command.confirmedByMemberId()
        )) {
            return resultMapper.toRoleHandoffTransitionResult(role, handoff);
        }
        if (handoff.getStatus() == RoleHandoffStatus.ACCEPTED) {
            throw handoffStateConflict("수락이 끝난 바통은 취소할 수 없습니다");
        }
        requireDeclaredConfirmer(
                handoff.getFromMemberId(),
                command.confirmedByMemberId(),
                "현재 담당자 명의로 바통 취소를 확인해 주세요"
        );
        requireRoleMatchesHandoff(role, handoff);

        handoff.cancel(command.confirmedByMemberId(), Instant.now(clock));
        role.cancelHandoff(handoff.getToMemberId());
        Role savedRole = repository.saveRole(role);
        RoleHandoff savedHandoff = repository.saveRoleHandoff(handoff);
        return resultMapper.toRoleHandoffTransitionResult(savedRole, savedHandoff);
    }

    private Role requireRoleForUpdate(UUID teamId, UUID seasonId, UUID roleId) {
        return repository.findRoleByTeamIdAndSeasonIdAndIdForUpdate(teamId, seasonId, roleId)
                .orElseThrow(() -> notFound("ROLE_NOT_FOUND", "역할을 찾을 수 없습니다"));
    }

    private RoleHandoff requireRoleHandoffForUpdate(
            UUID teamId,
            UUID seasonId,
            UUID roleId,
            UUID handoffId
    ) {
        return repository.findRoleHandoffByIdForUpdate(handoffId)
                .filter(handoff -> handoff.getTeamId().equals(teamId))
                .filter(handoff -> handoff.getSeasonId().equals(seasonId))
                .filter(handoff -> handoff.getRoleId().equals(roleId))
                .orElseThrow(() -> notFound(
                        "ROLE_HANDOFF_NOT_FOUND",
                        "역할 바통을 찾을 수 없습니다"
                ));
    }

    private void requireHandoffStatus(
            RoleHandoff handoff,
            RoleHandoffStatus expectedStatus,
            String message
    ) {
        if (handoff.getStatus() != expectedStatus) {
            throw handoffStateConflict(message);
        }
    }

    private void requireDeclaredConfirmer(
            UUID expectedMemberId,
            UUID confirmedByMemberId,
            String message
    ) {
        if (!Objects.equals(expectedMemberId, confirmedByMemberId)) {
            throw handoffStateConflict(message);
        }
    }

    private void requireRoleMatchesHandoff(Role role, RoleHandoff handoff) {
        if (!Objects.equals(role.getCurrentMemberId(), handoff.getFromMemberId())
                || !Objects.equals(role.getNextMemberId(), handoff.getToMemberId())) {
            throw handoffStateConflict(
                    "역할 담당자가 바통 준비 시점과 달라 최신 내용을 확인해 주세요"
            );
        }
    }

    private void validateRoleAssignmentDates(
            Season season,
            LocalDate assignmentStartDate,
            LocalDate assignmentEndDate
    ) {
        if (assignmentStartDate != null && !season.contains(assignmentStartDate)) {
            throw new DomainValidationException("역할 배정 시작일은 시즌 기간 안에 있어야 합니다");
        }
        if (assignmentEndDate != null && !season.contains(assignmentEndDate)) {
            throw new DomainValidationException("역할 배정 종료일은 시즌 기간 안에 있어야 합니다");
        }
    }

    private WorkspaceNotFoundException notFound(String code, String message) {
        return new WorkspaceNotFoundException(code, message);
    }

    private RoleHandoffStateConflictException handoffStateConflict(String message) {
        return new RoleHandoffStateConflictException(message);
    }
}
