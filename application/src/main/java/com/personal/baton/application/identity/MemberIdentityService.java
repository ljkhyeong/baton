package com.personal.baton.application.identity;

import com.personal.baton.application.identity.error.IdentityNotFoundException;
import com.personal.baton.application.identity.error.InactiveMemberIdentityException;
import com.personal.baton.application.identity.error.MemberIdentityConflictException;
import com.personal.baton.application.identity.port.in.MemberIdentityUseCase;
import com.personal.baton.application.identity.port.out.IdentityRepository;
import com.personal.baton.domain.identity.MemberIdentityBinding;
import com.personal.baton.domain.workspace.Member;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MemberIdentityService implements MemberIdentityUseCase {

    private final IdentityRepository repository;
    private final Clock clock;

    public MemberIdentityService(IdentityRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    @Transactional
    public MemberIdentityResult bindMember(
            UUID teamId,
            UUID memberId,
            AuthenticatedAccount authenticatedAccount
    ) {
        UUID accountId = authenticatedAccount.accountId();
        repository.findUserAccountByIdForUpdate(accountId)
                .orElseThrow(() -> new IdentityNotFoundException(
                        "ACCOUNT_NOT_FOUND",
                        "사용자 계정을 찾을 수 없습니다"
                ));

        Member member = repository.findMemberByTeamIdAndIdForUpdate(teamId, memberId)
                .orElseThrow(() -> new IdentityNotFoundException(
                        "MEMBER_NOT_FOUND",
                        "구성원을 찾을 수 없습니다"
                ));

        Optional<MemberIdentityBinding> memberBinding =
                repository.findBindingByMemberId(memberId);
        if (memberBinding.isPresent()) {
            MemberIdentityBinding binding = memberBinding.orElseThrow();
            if (binding.belongsTo(accountId)) {
                return result(binding);
            }
            throw new MemberIdentityConflictException();
        }

        Optional<MemberIdentityBinding> accountBinding =
                repository.findBindingByTeamIdAndUserAccountId(teamId, accountId);
        if (accountBinding.isPresent()) {
            MemberIdentityBinding binding = accountBinding.orElseThrow();
            if (binding.getMemberId().equals(memberId)) {
                return result(binding);
            }
            throw new MemberIdentityConflictException();
        }

        if (!member.isActive()) {
            throw new InactiveMemberIdentityException();
        }

        MemberIdentityBinding saved = repository.saveBinding(MemberIdentityBinding.bind(
                memberId,
                teamId,
                accountId,
                clock.instant()
        ));
        return result(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<MemberIdentityResult> findActiveMember(
            UUID teamId,
            AuthenticatedAccount authenticatedAccount
    ) {
        return repository.findBindingByTeamIdAndUserAccountId(
                        teamId,
                        authenticatedAccount.accountId()
                )
                .flatMap(binding -> repository.findMemberByTeamIdAndId(
                        teamId,
                        binding.getMemberId()
                ).filter(Member::isActive)
                        .map(member -> result(binding)));
    }

    private MemberIdentityResult result(MemberIdentityBinding binding) {
        return new MemberIdentityResult(
                binding.getUserAccountId(),
                binding.getTeamId(),
                binding.getMemberId(),
                binding.getBoundAt(),
                binding.getRole()
        );
    }
}
