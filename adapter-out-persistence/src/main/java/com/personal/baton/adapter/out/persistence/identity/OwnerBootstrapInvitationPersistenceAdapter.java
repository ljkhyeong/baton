package com.personal.baton.adapter.out.persistence.identity;

import com.personal.baton.application.identity.error.IdentityOperationException;
import com.personal.baton.application.identity.port.out.OwnerBootstrapInvitationRepository;
import com.personal.baton.domain.identity.OwnerBootstrapInvitation;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Repository;

@Repository
public class OwnerBootstrapInvitationPersistenceAdapter
        implements OwnerBootstrapInvitationRepository {

    private final OwnerBootstrapInvitationJpaRepository repository;

    public OwnerBootstrapInvitationPersistenceAdapter(
            OwnerBootstrapInvitationJpaRepository repository
    ) {
        this.repository = repository;
    }

    @Override
    public Optional<OwnerBootstrapInvitation> findByIdempotencyKeyHash(
            String idempotencyKeyHash
    ) {
        return repository.findByIdempotencyKeyHash(idempotencyKeyHash);
    }

    @Override
    public InvitationInsertResult insertIfAbsent(OwnerBootstrapInvitation invitation) {
        repository.insertIfAbsent(
                invitation.getId().toString(),
                invitation.getTeamId().toString(),
                invitation.getMemberId().toString(),
                invitation.getIdempotencyKeyHash(),
                invitation.getTokenHash(),
                invitation.getIssuedAt(),
                invitation.getExpiresAt()
        );
        OwnerBootstrapInvitation stored = repository.findByIdempotencyKeyHashForUpdate(
                        invitation.getIdempotencyKeyHash()
                )
                .orElseThrow(() -> new IllegalStateException(
                        "bootstrap 초대 upsert 결과를 찾을 수 없습니다"
                ));
        return new InvitationInsertResult(
                stored,
                stored.getId().equals(invitation.getId())
        );
    }

    @Override
    public Optional<OwnerBootstrapInvitation> findByTokenHashForUpdate(String tokenHash) {
        try {
            return repository.findByTokenHashForUpdate(tokenHash);
        } catch (PessimisticLockingFailureException exception) {
            throw conflict(exception);
        }
    }

    @Override
    public Optional<OwnerBootstrapInvitation> findByIdForUpdate(UUID invitationId) {
        try {
            return repository.findByIdForUpdate(invitationId);
        } catch (PessimisticLockingFailureException exception) {
            throw conflict(exception);
        }
    }

    @Override
    public OwnerBootstrapInvitation save(OwnerBootstrapInvitation invitation) {
        try {
            return repository.saveAndFlush(invitation);
        } catch (OptimisticLockingFailureException | PessimisticLockingFailureException exception) {
            throw conflict(exception);
        }
    }

    private IdentityOperationException conflict(RuntimeException cause) {
        return new IdentityOperationException(
                "BOOTSTRAP_INVITATION_CONFLICT",
                "bootstrap 초대 상태가 동시에 변경되었습니다",
                cause
        );
    }
}
