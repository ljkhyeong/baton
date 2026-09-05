package com.personal.baton.application.workspace;

import org.springframework.stereotype.Component;
import com.personal.baton.application.workspace.WorkspaceContentIdempotency.ContentCreationAttempt;
import com.personal.baton.application.workspace.error.RoleHandoffStateConflictException;
import com.personal.baton.application.workspace.error.RoleHandoffWarningConfirmationRequiredException;
import com.personal.baton.application.workspace.error.WorkspaceNotFoundException;
import com.personal.baton.application.workspace.port.in.WorkspacePeopleCommands.ConfirmRoleHandoffCommand;
import com.personal.baton.application.workspace.port.in.WorkspacePeopleCommands.PrepareRoleHandoffCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceContract.RoleHandoffTransitionResult;
import com.personal.baton.application.workspace.port.in.WorkspacePeopleCommands.TransferRoleHandoffCommand;
import com.personal.baton.application.workspace.port.out.WorkspacePeopleRepository;
import com.personal.baton.application.workspace.port.out.WorkspaceRecordsRepository;
import com.personal.baton.domain.workspace.ContentCreationOperation;
import com.personal.baton.domain.workspace.HandoffItem;
import com.personal.baton.domain.workspace.Role;
import com.personal.baton.domain.workspace.RoleHandoff;
import com.personal.baton.domain.workspace.RoleHandoffStatus;
import com.personal.baton.domain.workspace.Season;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Component
final class WorkspaceRoleHandoffCoordinator {

    private final WorkspacePeopleRepository peopleRepository;
    private final WorkspaceRecordsRepository recordsRepository;
    private final Clock clock;
    private final WorkspaceContentIdempotency contentIdempotency;
    private final WorkspaceMemberResolver memberResolver;
    private final WorkspaceRoleResolver roleResolver;
    private final WorkspaceRolePolicy rolePolicy;
    private final WorkspaceResultMapper resultMapper;
    private final BriefContinuitySignalRecorder briefContinuitySignalRecorder;

    WorkspaceRoleHandoffCoordinator(
            WorkspacePeopleRepository peopleRepository,
            WorkspaceRecordsRepository recordsRepository,
            Clock clock,
            WorkspaceContentIdempotency contentIdempotency,
            WorkspaceMemberResolver memberResolver,
            WorkspaceRoleResolver roleResolver,
            WorkspaceRolePolicy rolePolicy,
            WorkspaceResultMapper resultMapper,
            BriefContinuitySignalRecorder briefContinuitySignalRecorder
    ) {
        this.peopleRepository = peopleRepository;
        this.recordsRepository = recordsRepository;
        this.clock = clock;
        this.contentIdempotency = contentIdempotency;
        this.memberResolver = memberResolver;
        this.roleResolver = roleResolver;
        this.rolePolicy = rolePolicy;
        this.resultMapper = resultMapper;
        this.briefContinuitySignalRecorder = briefContinuitySignalRecorder;
    }

    RoleHandoffTransitionResult prepare(
            UUID teamId,
            Season season,
            UUID roleId,
            String idempotencyKey,
            PrepareRoleHandoffCommand command
    ) {
        UUID seasonId = season.getId();
        Role role = roleResolver.requireRoleForUpdate(teamId, seasonId, roleId);
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
            RoleHandoff existing = peopleRepository.findRoleHandoffById(attempt.replayResourceId())
                    .filter(handoff -> handoff.getTeamId().equals(teamId))
                    .filter(handoff -> handoff.getSeasonId().equals(seasonId))
                    .filter(handoff -> handoff.getRoleId().equals(roleId))
                    .orElseThrow(() -> contentIdempotency.missingResource(
                            ContentCreationOperation.ROLE_HANDOFF
                    ));
            return resultMapper.toRoleHandoffTransitionResult(role, existing);
        }
        if (peopleRepository.findOpenRoleHandoffByRoleIdWithSharedLock(roleId).isPresent()) {
            throw conflict("이 역할에는 이미 진행 중인 인수인계가 있습니다");
        }
        UUID fromMemberId = role.getCurrentMemberId();
        if (fromMemberId == null) {
            throw conflict("현재 담당자를 지정한 뒤 인수인계 준비를 시작해 주세요");
        }
        if (role.getAssignmentStartDate() == null) {
            throw conflict("현재 담당 시작일을 지정한 뒤 인수인계 준비를 시작해 주세요");
        }
        if (Objects.equals(fromMemberId, command.toMemberId())) {
            throw conflict("현재 담당자와 다음 담당자는 달라야 합니다");
        }
        if (role.getNextMemberId() != null
                && !Objects.equals(role.getNextMemberId(), command.toMemberId())) {
            throw conflict("역할에 지정된 다음 담당자와 인수인계 대상이 다릅니다");
        }
        memberResolver.requireActiveMembersForNewReferences(
                teamId,
                fromMemberId,
                command.toMemberId()
        );
        rolePolicy.validateAssignmentDates(
                season,
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
        Role savedRole = peopleRepository.saveRole(role);
        RoleHandoff savedHandoff = peopleRepository.saveRoleHandoff(handoff);
        briefContinuitySignalRecorder.reconcileSeason(teamId, seasonId);
        return resultMapper.toRoleHandoffTransitionResult(savedRole, savedHandoff);
    }

    RoleHandoffTransitionResult transfer(
            UUID teamId,
            UUID seasonId,
            UUID roleId,
            UUID handoffId,
            TransferRoleHandoffCommand command
    ) {
        Role role = roleResolver.requireRoleForUpdate(teamId, seasonId, roleId);
        RoleHandoff handoff = requireRoleHandoffForUpdate(
                teamId,
                seasonId,
                roleId,
                handoffId
        );
        if ((handoff.getStatus() == RoleHandoffStatus.TRANSFERRED
                || handoff.getStatus() == RoleHandoffStatus.ACCEPTED
                || handoff.getStatus() == RoleHandoffStatus.CANCELLED)
                && Objects.equals(
                handoff.getTransferredByMemberId(),
                command.confirmedByMemberId()
        )) {
            return resultMapper.toRoleHandoffTransitionResult(role, handoff);
        }
        requireStatus(
                handoff,
                RoleHandoffStatus.PREPARING,
                "준비 중인 인수인계만 전달할 수 있습니다"
        );
        requireDeclaredConfirmer(
                handoff.getFromMemberId(),
                command.confirmedByMemberId(),
                "현재 담당자 명의로 전달을 확인해 주세요"
        );
        requireRoleMatchesHandoff(role, handoff);
        memberResolver.requireActiveMembersForNewReferences(
                teamId,
                handoff.getFromMemberId(),
                handoff.getToMemberId()
        );

        List<HandoffItem> activeItems = recordsRepository.findHandoffItemsByRoleIds(List.of(roleId))
                .stream()
                .filter(item -> item.getArchivedAt() == null)
                .toList();
        int incompleteItemCount = (int) activeItems.stream()
                .filter(item -> !item.isCompleted())
                .count();
        int resourceCount = (int) recordsRepository.findRoleResourcesByRoleIds(List.of(roleId)).stream()
                .filter(resource -> resource.getArchivedAt() == null)
                .count();
        boolean hasWarning = RoleHandoff.hasWarnings(
                activeItems.size(),
                incompleteItemCount,
                resourceCount
        );
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
        RoleHandoff savedHandoff = peopleRepository.saveRoleHandoff(handoff);
        briefContinuitySignalRecorder.reconcileSeason(teamId, seasonId);
        return resultMapper.toRoleHandoffTransitionResult(role, savedHandoff);
    }

    RoleHandoffTransitionResult accept(
            UUID teamId,
            Season season,
            UUID roleId,
            UUID handoffId,
            ConfirmRoleHandoffCommand command
    ) {
        UUID seasonId = season.getId();
        Role role = roleResolver.requireRoleForUpdate(teamId, seasonId, roleId);
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
        requireStatus(
                handoff,
                RoleHandoffStatus.TRANSFERRED,
                "전달된 인수인계만 수락할 수 있습니다"
        );
        requireDeclaredConfirmer(
                handoff.getToMemberId(),
                command.confirmedByMemberId(),
                "다음 담당자 명의로 수락을 확인해 주세요"
        );
        requireRoleMatchesHandoff(role, handoff);
        memberResolver.requireActiveMembersForNewReferences(teamId, handoff.getToMemberId());
        rolePolicy.validateAssignmentDates(
                season,
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
        Role savedRole = peopleRepository.saveRole(role);
        RoleHandoff savedHandoff = peopleRepository.saveRoleHandoff(handoff);
        briefContinuitySignalRecorder.reconcileSeason(teamId, seasonId);
        return resultMapper.toRoleHandoffTransitionResult(savedRole, savedHandoff);
    }

    RoleHandoffTransitionResult cancel(
            UUID teamId,
            UUID seasonId,
            UUID roleId,
            UUID handoffId,
            ConfirmRoleHandoffCommand command
    ) {
        Role role = roleResolver.requireRoleForUpdate(teamId, seasonId, roleId);
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
            throw conflict("수락이 끝난 인수인계는 취소할 수 없습니다");
        }
        requireDeclaredConfirmer(
                handoff.getFromMemberId(),
                command.confirmedByMemberId(),
                "현재 담당자 명의로 인수인계 취소를 확인해 주세요"
        );
        requireRoleMatchesHandoff(role, handoff);

        handoff.cancel(command.confirmedByMemberId(), Instant.now(clock));
        role.cancelHandoff(handoff.getToMemberId());
        Role savedRole = peopleRepository.saveRole(role);
        RoleHandoff savedHandoff = peopleRepository.saveRoleHandoff(handoff);
        briefContinuitySignalRecorder.reconcileSeason(teamId, seasonId);
        return resultMapper.toRoleHandoffTransitionResult(savedRole, savedHandoff);
    }

    private RoleHandoff requireRoleHandoffForUpdate(
            UUID teamId,
            UUID seasonId,
            UUID roleId,
            UUID handoffId
    ) {
        return peopleRepository.findRoleHandoffByIdForUpdate(handoffId)
                .filter(handoff -> handoff.getTeamId().equals(teamId))
                .filter(handoff -> handoff.getSeasonId().equals(seasonId))
                .filter(handoff -> handoff.getRoleId().equals(roleId))
                .orElseThrow(() -> new WorkspaceNotFoundException(
                        "ROLE_HANDOFF_NOT_FOUND",
                        "역할 인수인계를 찾을 수 없습니다"
                ));
    }

    private void requireStatus(
            RoleHandoff handoff,
            RoleHandoffStatus expectedStatus,
            String message
    ) {
        if (handoff.getStatus() != expectedStatus) {
            throw conflict(message);
        }
    }

    private void requireDeclaredConfirmer(
            UUID expectedMemberId,
            UUID confirmedByMemberId,
            String message
    ) {
        if (!Objects.equals(expectedMemberId, confirmedByMemberId)) {
            throw conflict(message);
        }
    }

    private void requireRoleMatchesHandoff(Role role, RoleHandoff handoff) {
        if (!Objects.equals(role.getCurrentMemberId(), handoff.getFromMemberId())
                || !Objects.equals(role.getNextMemberId(), handoff.getToMemberId())) {
            throw conflict("역할 담당자가 인수인계 준비 시점과 달라 최신 내용을 확인해 주세요");
        }
    }

    private RoleHandoffStateConflictException conflict(String message) {
        return new RoleHandoffStateConflictException(message);
    }
}
