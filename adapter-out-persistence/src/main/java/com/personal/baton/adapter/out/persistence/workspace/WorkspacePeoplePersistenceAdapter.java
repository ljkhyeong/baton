package com.personal.baton.adapter.out.persistence.workspace;

import static com.personal.baton.adapter.out.persistence.PersistenceConstraintViolations.hasConstraint;

import com.personal.baton.application.workspace.error.MemberNameConflictException;
import com.personal.baton.application.workspace.error.RoleNameConflictException;
import com.personal.baton.application.workspace.error.WorkspaceContentConflictException;
import com.personal.baton.application.workspace.port.out.WorkspacePeopleRepository;
import com.personal.baton.domain.workspace.Member;
import com.personal.baton.domain.workspace.Role;
import com.personal.baton.domain.workspace.RoleHandoff;
import com.personal.baton.domain.workspace.RoleHandoffStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Repository;

@Repository
public class WorkspacePeoplePersistenceAdapter implements WorkspacePeopleRepository {

    private static final List<RoleHandoffStatus> OPEN_ROLE_HANDOFF_STATUSES =
            List.of(RoleHandoffStatus.PREPARING, RoleHandoffStatus.TRANSFERRED);

    private final MemberJpaRepository memberRepository;
    private final RoleJpaRepository roleRepository;
    private final RoleHandoffJpaRepository roleHandoffRepository;

    public WorkspacePeoplePersistenceAdapter(
            MemberJpaRepository memberRepository,
            RoleJpaRepository roleRepository,
            RoleHandoffJpaRepository roleHandoffRepository
    ) {
        this.memberRepository = memberRepository;
        this.roleRepository = roleRepository;
        this.roleHandoffRepository = roleHandoffRepository;
    }

    @Override
    public Member saveMember(Member member) {
        try {
            return memberRepository.saveAndFlush(member);
        } catch (OptimisticLockingFailureException | PessimisticLockingFailureException exception) {
            throw new WorkspaceContentConflictException(exception);
        } catch (DataIntegrityViolationException exception) {
            if (hasConstraint(exception, "uk_members_team_name")) {
                throw new MemberNameConflictException(exception);
            }
            throw exception;
        }
    }

    @Override
    public List<Member> saveMembers(List<Member> members) {
        try {
            return memberRepository.saveAllAndFlush(members);
        } catch (DataIntegrityViolationException exception) {
            if (hasConstraint(exception, "uk_members_team_name")) {
                throw new MemberNameConflictException(exception);
            }
            throw exception;
        }
    }

    @Override
    public Role saveRole(Role role) {
        try {
            return roleRepository.saveAndFlush(role);
        } catch (OptimisticLockingFailureException | PessimisticLockingFailureException exception) {
            throw new WorkspaceContentConflictException(exception);
        } catch (DataIntegrityViolationException exception) {
            if (hasConstraint(exception, "uk_roles_season_name")) {
                throw new RoleNameConflictException(exception);
            }
            throw exception;
        }
    }

    @Override
    public List<Role> saveRoles(List<Role> roles) {
        try {
            return roleRepository.saveAllAndFlush(roles);
        } catch (OptimisticLockingFailureException | PessimisticLockingFailureException exception) {
            throw new WorkspaceContentConflictException(exception);
        } catch (DataIntegrityViolationException exception) {
            if (hasConstraint(exception, "uk_roles_season_name")) {
                throw new RoleNameConflictException(exception);
            }
            throw exception;
        }
    }

    @Override
    public RoleHandoff saveRoleHandoff(RoleHandoff roleHandoff) {
        try {
            return roleHandoffRepository.saveAndFlush(roleHandoff);
        } catch (OptimisticLockingFailureException | PessimisticLockingFailureException exception) {
            throw new WorkspaceContentConflictException(exception);
        } catch (DataIntegrityViolationException exception) {
            if (hasConstraint(exception, "uk_role_handoffs_active_role")) {
                throw new WorkspaceContentConflictException(exception);
            }
            throw exception;
        }
    }

    @Override
    public Optional<Member> findMemberById(UUID memberId) {
        return memberRepository.findById(memberId);
    }

    @Override
    public List<Member> findMembersByTeamIdAndIdsWithSharedLock(
            UUID teamId,
        List<UUID> memberIds
    ) {
        try {
            return memberRepository.findAllWithSharedLockByTeamIdAndIdInOrderByIdAsc(
                    teamId,
                    memberIds
            );
        } catch (PessimisticLockingFailureException exception) {
            throw new WorkspaceContentConflictException(exception);
        }
    }

    @Override
    public Optional<Role> findRoleById(UUID roleId) {
        return roleRepository.findById(roleId);
    }

    @Override
    public Optional<Role> findRoleByTeamIdAndSeasonIdAndIdForUpdate(
            UUID teamId,
            UUID seasonId,
            UUID roleId
    ) {
        try {
            return roleRepository.findForUpdateByTeamIdAndSeasonIdAndId(
                    teamId,
                    seasonId,
                    roleId
            );
        } catch (PessimisticLockingFailureException exception) {
            throw new WorkspaceContentConflictException(exception);
        }
    }

    @Override
    public List<Role> findRolesByTeamIdAndSeasonIdAndIdsWithSharedLock(
            UUID teamId,
            UUID seasonId,
        List<UUID> roleIds
    ) {
        try {
            return roleRepository.findAllWithSharedLockByTeamIdAndSeasonIdAndIdInOrderByIdAsc(
                    teamId,
                    seasonId,
                    roleIds
            );
        } catch (PessimisticLockingFailureException exception) {
            throw new WorkspaceContentConflictException(exception);
        }
    }

    @Override
    public Optional<RoleHandoff> findRoleHandoffById(UUID handoffId) {
        return roleHandoffRepository.findById(handoffId);
    }

    @Override
    public Optional<RoleHandoff> findRoleHandoffByIdForUpdate(UUID handoffId) {
        try {
            return roleHandoffRepository.findForUpdateById(handoffId);
        } catch (PessimisticLockingFailureException exception) {
            throw new WorkspaceContentConflictException(exception);
        }
    }

    @Override
    public Optional<RoleHandoff> findOpenRoleHandoffByRoleIdWithSharedLock(UUID roleId) {
        try {
            return roleHandoffRepository.findOpenWithSharedLockByRoleIdAndStatusIn(
                    roleId,
                    OPEN_ROLE_HANDOFF_STATUSES
            );
        } catch (PessimisticLockingFailureException exception) {
            throw new WorkspaceContentConflictException(exception);
        }
    }

    @Override
    public List<Member> findMembersByTeamId(UUID teamId) {
        return memberRepository.findAllByTeamIdOrderByNameAsc(teamId);
    }

    @Override
    public List<Role> findRolesByTeamIdAndSeasonId(UUID teamId, UUID seasonId) {
        return roleRepository.findAllByTeamIdAndSeasonIdOrderByNameAsc(teamId, seasonId);
    }

    @Override
    public List<UUID> findExistingRoleIds(UUID teamId, UUID seasonId, List<UUID> roleIds) {
        return roleRepository.findExistingIds(teamId, seasonId, roleIds);
    }

    @Override
    public List<RoleHandoff> findRoleHandoffsByRoleIds(List<UUID> roleIds) {
        return roleHandoffRepository.findAllByRoleIdInOrderByRoleIdAscPreparedAtDescIdAsc(
                roleIds
        );
    }

    @Override
    public boolean existsOpenRoleHandoffBySeasonId(UUID seasonId) {
        return roleHandoffRepository.existsBySeasonIdAndStatusIn(
                seasonId,
                OPEN_ROLE_HANDOFF_STATUSES
        );
    }
}

