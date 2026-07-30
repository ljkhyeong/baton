package com.personal.baton.application.identity.port.out;

import com.personal.baton.domain.identity.MemberIdentityBinding;
import com.personal.baton.domain.identity.UserAccount;
import com.personal.baton.domain.workspace.Member;
import java.util.Optional;
import java.util.UUID;

public interface IdentityRepository {

    Optional<UserAccount> findUserAccountByIdForUpdate(UUID accountId);

    Optional<Member> findMemberByTeamIdAndIdForUpdate(UUID teamId, UUID memberId);

    Optional<Member> findMemberByTeamIdAndId(UUID teamId, UUID memberId);

    Optional<MemberIdentityBinding> findBindingByMemberId(UUID memberId);

    Optional<MemberIdentityBinding> findBindingByTeamIdAndUserAccountId(
            UUID teamId,
            UUID accountId
    );

    Optional<MemberIdentityBinding> findOwnerBindingByTeamId(UUID teamId);

    MemberIdentityBinding saveBinding(MemberIdentityBinding binding);
}
