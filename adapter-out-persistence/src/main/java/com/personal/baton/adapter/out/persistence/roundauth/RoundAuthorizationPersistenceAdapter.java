package com.personal.baton.adapter.out.persistence.roundauth;

import com.personal.baton.application.roundauth.error.AccountMembershipConflictException;
import com.personal.baton.application.roundauth.port.out.RoundAuthorizationRepository;
import com.personal.baton.application.roundauth.port.out.RoundAuthorizationRepository.RoomMappingCreationResult;
import com.personal.baton.adapter.out.persistence.roundauth.RoundRoomMappingCreationTransaction.MappingInsertException;
import com.personal.baton.adapter.out.persistence.roundauth.RoundRoomMappingCreationTransaction.TombstoneInsertException;
import com.personal.baton.domain.roundauth.AccountTeamMembership;
import com.personal.baton.domain.roundauth.RoundRoomMapping;
import com.personal.baton.domain.roundauth.RoundRoomTombstone;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

@Repository
public class RoundAuthorizationPersistenceAdapter implements RoundAuthorizationRepository {

    private final AccountTeamMembershipJpaRepository membershipRepository;
    private final RoundRoomTombstoneJpaRepository tombstoneRepository;
    private final RoundRoomMappingJpaRepository mappingRepository;
    private final RoundRoomMappingCreationTransaction mappingCreationTransaction;

    public RoundAuthorizationPersistenceAdapter(
            AccountTeamMembershipJpaRepository membershipRepository,
            RoundRoomTombstoneJpaRepository tombstoneRepository,
            RoundRoomMappingJpaRepository mappingRepository,
            RoundRoomMappingCreationTransaction mappingCreationTransaction
    ) {
        this.membershipRepository = membershipRepository;
        this.tombstoneRepository = tombstoneRepository;
        this.mappingRepository = mappingRepository;
        this.mappingCreationTransaction = mappingCreationTransaction;
    }

    @Override
    public AccountTeamMembership saveMembership(AccountTeamMembership membership) {
        try {
            return membershipRepository.saveAndFlush(membership);
        } catch (DataIntegrityViolationException exception) {
            if (hasConstraint(exception, "uk_account_team_memberships_account_team")
                    || hasConstraint(exception, "uk_account_team_memberships_member")) {
                throw new AccountMembershipConflictException(
                        "계정 또는 구성원이 이미 팀 멤버십에 연결되어 있습니다",
                        exception
                );
            }
            throw exception;
        }
    }

    @Override
    public Optional<AccountTeamMembership> findMembership(UUID accountId, UUID teamId) {
        return membershipRepository.findByAccountIdAndTeamId(accountId, teamId);
    }

    @Override
    public Optional<AccountTeamMembership> findMembershipByMemberId(UUID memberId) {
        return membershipRepository.findByMemberId(memberId);
    }

    @Override
    public RoundRoomTombstone saveTombstone(RoundRoomTombstone tombstone) {
        return tombstoneRepository.saveAndFlush(tombstone);
    }

    @Override
    public Optional<RoundRoomTombstone> findTombstoneForUpdate(String roomId) {
        return tombstoneRepository.findByRoomIdForUpdate(roomId);
    }

    @Override
    public RoomMappingCreationResult createMapping(
            RoundRoomTombstone tombstone,
            RoundRoomMapping mapping
    ) {
        try {
            RoundRoomMapping created = mappingCreationTransaction.create(
                    tombstone,
                    mapping
            );
            return new RoomMappingCreationResult.Created(created);
        } catch (TombstoneInsertException exception) {
            RuntimeException violation = exception.persistenceFailure();
            if (hasConstraint(violation, "primary")) {
                return new RoomMappingCreationResult.RoomIdUnavailable();
            }
            throw violation;
        } catch (MappingInsertException exception) {
            RuntimeException violation = exception.persistenceFailure();
            if (hasConstraint(violation, "uk_round_room_mappings_room")) {
                return new RoomMappingCreationResult.RoomIdUnavailable();
            }
            if (hasConstraint(violation, "uk_round_room_mappings_resource")) {
                return mappingCreationTransaction
                        .findByResourceId(mapping.getResourceId())
                        .<RoomMappingCreationResult>map(
                                RoomMappingCreationResult.ResourceAlreadyMapped::new
                        )
                        .orElseGet(RoomMappingCreationResult.ResourceBecameAvailable::new);
            }
            throw violation;
        }
    }

    @Override
    public Optional<RoundRoomMapping> findMappingByRoomId(String roomId) {
        return mappingRepository.findByRoomId(roomId);
    }

    @Override
    public Optional<RoundRoomMapping> findMappingByResourceId(UUID resourceId) {
        return mappingRepository.findByResourceId(resourceId);
    }

    @Override
    public void deleteMapping(RoundRoomMapping mapping) {
        mappingRepository.delete(mapping);
        mappingRepository.flush();
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
