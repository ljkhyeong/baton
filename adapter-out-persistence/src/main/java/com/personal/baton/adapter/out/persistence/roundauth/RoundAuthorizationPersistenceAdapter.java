package com.personal.baton.adapter.out.persistence.roundauth;

import com.personal.baton.application.roundauth.error.AccountMembershipConflictException;
import com.personal.baton.application.roundauth.error.RoundRoomConflictException;
import com.personal.baton.application.roundauth.port.out.RoundAuthorizationRepository;
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

    public RoundAuthorizationPersistenceAdapter(
            AccountTeamMembershipJpaRepository membershipRepository,
            RoundRoomTombstoneJpaRepository tombstoneRepository,
            RoundRoomMappingJpaRepository mappingRepository
    ) {
        this.membershipRepository = membershipRepository;
        this.tombstoneRepository = tombstoneRepository;
        this.mappingRepository = mappingRepository;
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
        try {
            return tombstoneRepository.saveAndFlush(tombstone);
        } catch (DataIntegrityViolationException exception) {
            if (hasConstraint(exception, "primary")) {
                throw new RoundRoomConflictException(
                        "이미 사용된 ROUND 방 식별자는 재사용할 수 없습니다",
                        exception
                );
            }
            throw exception;
        }
    }

    @Override
    public Optional<RoundRoomTombstone> findTombstone(String roomId) {
        return tombstoneRepository.findById(roomId);
    }

    @Override
    public Optional<RoundRoomTombstone> findTombstoneForUpdate(String roomId) {
        return tombstoneRepository.findByRoomIdForUpdate(roomId);
    }

    @Override
    public RoundRoomMapping saveMapping(RoundRoomMapping mapping) {
        try {
            return mappingRepository.saveAndFlush(mapping);
        } catch (DataIntegrityViolationException exception) {
            if (hasConstraint(exception, "uk_round_room_mappings_room")
                    || hasConstraint(exception, "uk_round_room_mappings_resource")) {
                throw new RoundRoomConflictException(
                        "ROUND 방 또는 자료에 활성 매핑이 이미 존재합니다",
                        exception
                );
            }
            throw exception;
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
