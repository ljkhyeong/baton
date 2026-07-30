package com.personal.baton.adapter.out.persistence.identity;

import com.personal.baton.application.identity.error.IdentityOperationException;
import com.personal.baton.application.identity.port.out.MemberInvitationRepository;
import com.personal.baton.application.identity.port.out.MemberInvitationRepository.InvitationObservation;
import com.personal.baton.domain.identity.MemberInvitation;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Repository;

@Repository
public class MemberInvitationPersistenceAdapter implements MemberInvitationRepository {

    private final MemberInvitationJpaRepository repository;

    public MemberInvitationPersistenceAdapter(MemberInvitationJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<MemberInvitation> findByIdempotencyKeyHash(String idempotencyKeyHash) {
        return repository.findByIdempotencyKeyHash(idempotencyKeyHash);
    }

    @Override
    public InvitationInsertResult insertIfAbsent(MemberInvitation invitation) {
        repository.insertIfAbsent(
                invitation.getId().toString(),
                invitation.getTeamId().toString(),
                invitation.getMemberId().toString(),
                invitation.getIssuedByAccountId().toString(),
                invitation.getIdempotencyKeyHash(),
                invitation.getTokenHash(),
                invitation.getIssuedAt(),
                invitation.getExpiresAt()
        );
        MemberInvitation stored = repository.findByIdempotencyKeyHashForUpdate(
                        invitation.getIdempotencyKeyHash()
                )
                .orElseThrow(() -> new IllegalStateException(
                        "구성원 초대 upsert 결과를 찾을 수 없습니다"
                ));
        return new InvitationInsertResult(
                stored,
                stored.getId().equals(invitation.getId())
        );
    }

    @Override
    public Optional<MemberInvitation> findByTokenHash(String tokenHash) {
        return repository.findByTokenHash(tokenHash);
    }

    @Override
    public Optional<InvitationObservation> findObservationByTokenHash(String tokenHash) {
        return repository.findObservationByTokenHash(tokenHash)
                .map(observation -> new InvitationObservation(
                        observation.getInvitationId(),
                        observation.getTeamId(),
                        observation.getMemberId(),
                        observation.getIssuedByAccountId(),
                        observation.getExpiresAt(),
                        observation.getRevokedAt(),
                        observation.getConsumedAt()
                ));
    }

    @Override
    public Optional<MemberInvitation> findByTokenHashForUpdate(String tokenHash) {
        try {
            return repository.findByTokenHashForUpdate(tokenHash);
        } catch (PessimisticLockingFailureException exception) {
            throw conflict(exception);
        }
    }

    @Override
    public Optional<MemberInvitation> findByTeamIdAndIdForUpdate(
            UUID teamId,
            UUID invitationId
    ) {
        try {
            return repository.findByTeamIdAndIdForUpdate(teamId, invitationId);
        } catch (PessimisticLockingFailureException exception) {
            throw conflict(exception);
        }
    }

    @Override
    public Optional<MemberInvitation> findOpenByTeamIdAndMemberIdForUpdate(
            UUID teamId,
            UUID memberId,
            Instant now
    ) {
        try {
            return repository.findOpenByTeamIdAndMemberIdForUpdate(teamId, memberId, now)
                    .stream()
                    .findFirst();
        } catch (PessimisticLockingFailureException exception) {
            throw conflict(exception);
        }
    }

    @Override
    public List<MemberInvitation> findAllOpenByTeamId(UUID teamId, Instant now) {
        return repository.findAllOpenByTeamId(teamId, now);
    }

    @Override
    public MemberInvitation save(MemberInvitation invitation) {
        try {
            return repository.saveAndFlush(invitation);
        } catch (OptimisticLockingFailureException | PessimisticLockingFailureException exception) {
            throw conflict(exception);
        }
    }

    private IdentityOperationException conflict(RuntimeException cause) {
        return new IdentityOperationException(
                "MEMBER_INVITATION_CONFLICT",
                "구성원 초대 상태가 동시에 변경되었습니다",
                cause
        );
    }
}
