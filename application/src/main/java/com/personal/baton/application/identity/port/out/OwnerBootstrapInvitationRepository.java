package com.personal.baton.application.identity.port.out;

import com.personal.baton.domain.identity.OwnerBootstrapInvitation;
import java.util.Optional;
import java.util.UUID;

public interface OwnerBootstrapInvitationRepository {

    Optional<OwnerBootstrapInvitation> findByIdempotencyKeyHash(String idempotencyKeyHash);

    InvitationInsertResult insertIfAbsent(OwnerBootstrapInvitation invitation);

    Optional<OwnerBootstrapInvitation> findByTokenHash(String tokenHash);

    Optional<OwnerBootstrapInvitation> findByTokenHashForUpdate(String tokenHash);

    Optional<OwnerBootstrapInvitation> findByIdForUpdate(UUID invitationId);

    OwnerBootstrapInvitation save(OwnerBootstrapInvitation invitation);

    record InvitationInsertResult(
            OwnerBootstrapInvitation invitation,
            boolean created
    ) {
    }
}
