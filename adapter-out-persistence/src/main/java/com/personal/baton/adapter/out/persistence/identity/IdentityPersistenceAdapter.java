package com.personal.baton.adapter.out.persistence.identity;

import com.personal.baton.adapter.out.persistence.workspace.MemberJpaRepository;
import com.personal.baton.adapter.out.persistence.workspace.TeamJpaRepository;
import com.personal.baton.application.identity.error.MemberIdentityConflictException;
import com.personal.baton.application.identity.port.out.IdentityRepository;
import com.personal.baton.domain.identity.MemberIdentityBinding;
import com.personal.baton.domain.identity.MemberIdentityRole;
import com.personal.baton.domain.identity.UserAccount;
import com.personal.baton.domain.workspace.Member;
import com.personal.baton.domain.workspace.Team;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Repository;

@Repository
public class IdentityPersistenceAdapter implements IdentityRepository {

    private final UserAccountJpaRepository userAccountRepository;
    private final MemberIdentityBindingJpaRepository bindingRepository;
    private final MemberJpaRepository memberRepository;
    private final TeamJpaRepository teamRepository;

    public IdentityPersistenceAdapter(
            UserAccountJpaRepository userAccountRepository,
            MemberIdentityBindingJpaRepository bindingRepository,
            MemberJpaRepository memberRepository,
            TeamJpaRepository teamRepository
    ) {
        this.userAccountRepository = userAccountRepository;
        this.bindingRepository = bindingRepository;
        this.memberRepository = memberRepository;
        this.teamRepository = teamRepository;
    }

    @Override
    public Optional<UserAccount> findUserAccountById(UUID accountId) {
        return userAccountRepository.findById(accountId);
    }

    @Override
    public Optional<UserAccount> findUserAccountByIdForUpdate(UUID accountId) {
        try {
            return userAccountRepository.findByIdForUpdate(accountId);
        } catch (PessimisticLockingFailureException exception) {
            throw new MemberIdentityConflictException(exception);
        }
    }

    @Override
    public Optional<Team> findTeamById(UUID teamId) {
        return teamRepository.findById(teamId);
    }

    @Override
    public Optional<Member> findMemberByTeamIdAndIdForUpdate(UUID teamId, UUID memberId) {
        try {
            return memberRepository.findByTeamIdAndIdForUpdate(teamId, memberId);
        } catch (PessimisticLockingFailureException exception) {
            throw new MemberIdentityConflictException(exception);
        }
    }

    @Override
    public Optional<Member> findMemberByTeamIdAndId(UUID teamId, UUID memberId) {
        return memberRepository.findByTeamIdAndId(teamId, memberId);
    }

    @Override
    public Optional<MemberIdentityBinding> findBindingByMemberId(UUID memberId) {
        return bindingRepository.findById(memberId);
    }

    @Override
    public Optional<MemberIdentityBinding> findBindingByTeamIdAndUserAccountId(
            UUID teamId,
            UUID accountId
    ) {
        return bindingRepository.findByTeamIdAndUserAccountId(teamId, accountId);
    }

    @Override
    public Optional<MemberIdentityBinding> findOwnerBindingByTeamId(UUID teamId) {
        return bindingRepository.findByTeamIdAndRole(teamId, MemberIdentityRole.OWNER);
    }

    @Override
    public MemberIdentityBinding saveBinding(MemberIdentityBinding binding) {
        try {
            return bindingRepository.saveAndFlush(binding);
        } catch (OptimisticLockingFailureException | PessimisticLockingFailureException exception) {
            throw new MemberIdentityConflictException(exception);
        } catch (DataIntegrityViolationException exception) {
            if (hasConstraint(exception, "PRIMARY")
                    || hasConstraint(
                            exception,
                            "uk_member_identity_bindings_team_account"
                    )
                    || hasConstraint(
                            exception,
                            "uk_member_identity_bindings_team_owner"
                    )) {
                throw new MemberIdentityConflictException(exception);
            }
            throw exception;
        }
    }

    private boolean hasConstraint(Throwable throwable, String expectedName) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof ConstraintViolationException constraintViolation
                    && constraintViolation.getConstraintName() != null) {
                String actualName = constraintViolation.getConstraintName()
                        .replace("`", "")
                        .toLowerCase(Locale.ROOT);
                String normalizedExpectedName = expectedName.toLowerCase(Locale.ROOT);
                if (actualName.equals(normalizedExpectedName)
                        || actualName.endsWith("." + normalizedExpectedName)) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }
}
