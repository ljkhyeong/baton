package com.personal.baton.adapter.out.persistence.roundauth;

import static com.personal.baton.adapter.out.persistence.PersistenceConstraintViolations.hasConstraint;

import com.personal.baton.application.roundauth.port.out.RoundAuthorizationRepository;
import com.personal.baton.application.roundauth.port.out.RoundAuthorizationRepository.MembershipClaimResult;
import com.personal.baton.application.roundauth.port.out.RoundAuthorizationRepository.RoomMappingCreationResult;
import com.personal.baton.adapter.out.persistence.roundauth.AccountTeamMembershipClaimTransaction.MembershipInsertException;
import com.personal.baton.adapter.out.persistence.roundauth.RoundRoomMappingCreationTransaction.MappingInsertException;
import com.personal.baton.adapter.out.persistence.roundauth.RoundRoomMappingCreationTransaction.TombstoneInsertException;
import com.personal.baton.domain.roundauth.AccountTeamMembership;
import com.personal.baton.domain.roundauth.RoundRoomMapping;
import com.personal.baton.domain.roundauth.RoundRoomTombstone;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

@Repository
public class RoundAuthorizationPersistenceAdapter implements RoundAuthorizationRepository {

    private final AccountTeamMembershipJpaRepository membershipRepository;
    private final RoundRoomTombstoneJpaRepository tombstoneRepository;
    private final RoundRoomMappingJpaRepository mappingRepository;
    private final AccountTeamMembershipClaimTransaction membershipClaimTransaction;
    private final RoundRoomMappingCreationTransaction mappingCreationTransaction;

    public RoundAuthorizationPersistenceAdapter(
            AccountTeamMembershipJpaRepository membershipRepository,
            RoundRoomTombstoneJpaRepository tombstoneRepository,
            RoundRoomMappingJpaRepository mappingRepository,
            AccountTeamMembershipClaimTransaction membershipClaimTransaction,
            RoundRoomMappingCreationTransaction mappingCreationTransaction
    ) {
        this.membershipRepository = membershipRepository;
        this.tombstoneRepository = tombstoneRepository;
        this.mappingRepository = mappingRepository;
        this.membershipClaimTransaction = membershipClaimTransaction;
        this.mappingCreationTransaction = mappingCreationTransaction;
    }

    @Override
    public MembershipClaimResult claimMembership(AccountTeamMembership membership) {
        try {
            return new MembershipClaimResult.Claimed(
                    membershipClaimTransaction.create(membership)
            );
        } catch (MembershipInsertException exception) {
            RuntimeException violation = exception.persistenceFailure();
            if (hasConstraint(violation, "uk_account_team_memberships_account_team")) {
                return membershipClaimTransaction.findByAccountAndTeam(
                                membership.getAccountId(),
                                membership.getTeamId()
                        )
                        .<MembershipClaimResult>map(
                                MembershipClaimResult.AlreadyClaimed::new
                        )
                        .orElseThrow(() -> missingMembershipWinner(violation));
            }
            if (hasConstraint(violation, "uk_account_team_memberships_member")) {
                return membershipClaimTransaction.findByMemberId(membership.getMemberId())
                        .<MembershipClaimResult>map(
                                MembershipClaimResult.AlreadyClaimed::new
                        )
                        .orElseThrow(() -> missingMembershipWinner(violation));
            }
            throw violation;
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
        return tombstoneRepository.findForUpdateByRoomId(roomId);
    }

    @Override
    public Optional<RoundRoomTombstone> findTombstoneForShare(String roomId) {
        return tombstoneRepository.findForShareByRoomId(roomId);
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
    public List<RoundRoomMapping> findMappingsByTeamIdAndSeasonId(
            UUID teamId,
            UUID seasonId
    ) {
        return mappingRepository.findAllByTeamIdAndSeasonIdOrderByResourceIdAsc(
                teamId,
                seasonId
        );
    }

    @Override
    public void deleteMapping(RoundRoomMapping mapping) {
        mappingRepository.delete(mapping);
        mappingRepository.flush();
    }

    private IllegalStateException missingMembershipWinner(RuntimeException violation) {
        return new IllegalStateException(
                "계정 멤버십 unique 경쟁의 승자를 찾을 수 없습니다",
                violation
        );
    }
}
