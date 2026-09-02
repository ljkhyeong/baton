package com.personal.baton.application.workspace;

import org.springframework.stereotype.Component;
import com.personal.baton.application.workspace.error.RoleHandoffStateConflictException;
import com.personal.baton.application.workspace.error.WorkspaceNotFoundException;
import com.personal.baton.application.workspace.port.out.WorkspacePeopleRepository;
import com.personal.baton.domain.workspace.DomainValidationException;
import com.personal.baton.domain.workspace.Role;
import com.personal.baton.domain.workspace.RoleHandoffStatus;
import com.personal.baton.domain.workspace.Season;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Component
final class WorkspaceRolePolicy {

    private final WorkspacePeopleRepository repository;

    WorkspaceRolePolicy(WorkspacePeopleRepository repository) {
        this.repository = repository;
    }

    void requireUpdateAllowed(
            Role role,
            UUID currentMemberId,
            UUID nextMemberId,
            LocalDate assignmentStartDate,
            LocalDate assignmentEndDate
    ) {
        repository.findOpenRoleHandoffByRoleIdWithSharedLock(role.getId()).ifPresent(handoff -> {
            if (handoff.getStatus() == RoleHandoffStatus.TRANSFERRED) {
                throw new RoleHandoffStateConflictException(
                        "전달된 바통의 역할 내용은 수락 또는 취소 전까지 바꿀 수 없습니다"
                );
            }
            if (!Objects.equals(role.getCurrentMemberId(), currentMemberId)
                    || !Objects.equals(role.getNextMemberId(), nextMemberId)
                    || !Objects.equals(role.getAssignmentStartDate(), assignmentStartDate)
                    || !Objects.equals(role.getAssignmentEndDate(), assignmentEndDate)) {
                throw new RoleHandoffStateConflictException(
                        "진행 중인 바통의 담당자와 담당 기간은 수락 또는 취소 전까지 바꿀 수 없습니다"
                );
            }
        });
    }

    void requireEditableHandoffRoles(
            UUID teamId,
            UUID seasonId,
            UUID... candidateRoleIds
    ) {
        List<UUID> roleIds = Arrays.stream(candidateRoleIds)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
        List<Role> roles = repository.findRolesByTeamIdAndSeasonIdAndIdsWithSharedLock(
                teamId,
                seasonId,
                roleIds
        );
        if (roles.size() != roleIds.size()) {
            throw new WorkspaceNotFoundException("ROLE_NOT_FOUND", "역할을 찾을 수 없습니다");
        }
        for (UUID roleId : roleIds) {
            repository.findOpenRoleHandoffByRoleIdWithSharedLock(roleId)
                    .filter(handoff -> handoff.getStatus() == RoleHandoffStatus.TRANSFERRED)
                    .ifPresent(handoff -> {
                        throw new RoleHandoffStateConflictException(
                                "전달된 바통의 항목과 자료는 수락 또는 취소 전까지 바꿀 수 없습니다"
                        );
                    });
        }
    }

    void validateAssignmentDates(
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
}
