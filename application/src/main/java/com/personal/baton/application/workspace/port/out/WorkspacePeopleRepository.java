package com.personal.baton.application.workspace.port.out;

import com.personal.baton.domain.workspace.Member;
import com.personal.baton.domain.workspace.Role;
import com.personal.baton.domain.workspace.RoleHandoff;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkspacePeopleRepository {

    Member saveMember(Member member);

    List<Member> saveMembers(List<Member> members);

    Role saveRole(Role role);

    List<Role> saveRoles(List<Role> roles);

    RoleHandoff saveRoleHandoff(RoleHandoff roleHandoff);

    Optional<Member> findMemberById(UUID memberId);

    List<Member> findMembersByTeamIdAndIdsWithSharedLock(UUID teamId, List<UUID> memberIds);

    Optional<Role> findRoleById(UUID roleId);

    Optional<Role> findRoleByTeamIdAndSeasonIdAndIdForUpdate(
            UUID teamId,
            UUID seasonId,
            UUID roleId
    );

    List<Role> findRolesByTeamIdAndSeasonIdAndIdsWithSharedLock(
            UUID teamId,
            UUID seasonId,
            List<UUID> roleIds
    );

    Optional<RoleHandoff> findRoleHandoffById(UUID handoffId);

    Optional<RoleHandoff> findRoleHandoffByIdForUpdate(UUID handoffId);

    Optional<RoleHandoff> findOpenRoleHandoffByRoleIdWithSharedLock(UUID roleId);

    List<Member> findMembersByTeamId(UUID teamId);

    List<Role> findRolesByTeamIdAndSeasonId(UUID teamId, UUID seasonId);

    boolean existsRoleAssignmentOutsideRange(UUID teamId, UUID seasonId, LocalDate startDate, LocalDate endDate);

    List<Role> findRolesByTeamIdAndSeasonIdAndIds(UUID teamId, UUID seasonId, List<UUID> roleIds);

    List<UUID> findExistingRoleIds(UUID teamId, UUID seasonId, List<UUID> roleIds);

    List<String> findRoleNames(UUID teamId, UUID seasonId, List<UUID> roleIds);

    List<RoleHandoff> findRoleHandoffsByRoleIds(List<UUID> roleIds);

    List<TransferredHandoff> findTransferredHandoffs(UUID teamId, UUID seasonId, UUID memberId);

    interface TransferredHandoff {
        UUID getId();
        UUID getRoleId();
        String getRoleName();
        Instant getTransferredAt();
    }

    boolean existsOpenRoleHandoffBySeasonId(UUID seasonId);

    boolean existsOpenRoleHandoffOutsideRange(UUID teamId, UUID seasonId, LocalDate startDate, LocalDate endDate);
}
