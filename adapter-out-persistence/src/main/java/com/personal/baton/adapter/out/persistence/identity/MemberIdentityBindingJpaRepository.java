package com.personal.baton.adapter.out.persistence.identity;

import com.personal.baton.domain.identity.MemberIdentityBinding;
import com.personal.baton.domain.identity.MemberIdentityRole;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberIdentityBindingJpaRepository
        extends JpaRepository<MemberIdentityBinding, UUID> {

    Optional<MemberIdentityBinding> findByTeamIdAndUserAccountId(
            UUID teamId,
            UUID userAccountId
    );

    Optional<MemberIdentityBinding> findByTeamIdAndRole(
            UUID teamId,
            MemberIdentityRole role
    );
}
