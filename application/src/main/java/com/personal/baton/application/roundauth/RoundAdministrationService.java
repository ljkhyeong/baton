package com.personal.baton.application.roundauth;

import com.personal.baton.application.roundauth.error.AccountMembershipConflictException;
import com.personal.baton.application.identity.port.out.IdentityRepository;
import com.personal.baton.application.identity.error.AccountDeactivatedException;
import com.personal.baton.domain.identity.Account;
import com.personal.baton.application.roundauth.error.RoundRoomConflictException;
import com.personal.baton.application.roundauth.error.RoundRoomNotFoundException;
import com.personal.baton.application.roundauth.port.in.RoundAdministrationUseCase;
import com.personal.baton.application.roundauth.port.out.RoundAuthorizationRepository;
import com.personal.baton.application.roundauth.port.out.RoundAuthorizationRepository.MembershipClaimResult;
import com.personal.baton.application.roundauth.port.out.RoundAuthorizationRepository.RoomMappingCreationResult;
import com.personal.baton.application.roundauth.port.out.RoundRoomIdGenerator;
import com.personal.baton.application.workspace.port.in.VerifyWorkspaceAccessUseCase;
import com.personal.baton.application.workspace.port.out.WorkspacePeopleRepository;
import com.personal.baton.application.workspace.port.out.WorkspaceRecordsRepository;
import com.personal.baton.domain.roundauth.AccountTeamMembership;
import com.personal.baton.domain.roundauth.RoundRoomId;
import com.personal.baton.domain.roundauth.RoundRoomMapping;
import com.personal.baton.domain.roundauth.RoundRoomTombstone;
import com.personal.baton.domain.workspace.Member;
import com.personal.baton.domain.workspace.RoleResource;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class RoundAdministrationService implements RoundAdministrationUseCase {

    private static final int ROOM_ID_GENERATION_ATTEMPTS = 8;

    private final RoundAuthorizationRepository roundRepository;
    private final WorkspacePeopleRepository peopleRepository;
    private final WorkspaceRecordsRepository recordsRepository;
    private final VerifyWorkspaceAccessUseCase workspaceAccess;
    private final RoundRoomIdGenerator roomIdGenerator;
    private final ActiveAccountTeamMembershipVerifier membershipVerifier;
    private final IdentityRepository identities;
    private final Clock clock;

    public RoundAdministrationService(
            RoundAuthorizationRepository roundRepository,
            WorkspacePeopleRepository peopleRepository,
            WorkspaceRecordsRepository recordsRepository,
            VerifyWorkspaceAccessUseCase workspaceAccess,
            RoundRoomIdGenerator roomIdGenerator,
            ActiveAccountTeamMembershipVerifier membershipVerifier,
            Clock clock,
            IdentityRepository identities
    ) {
        this.roundRepository = roundRepository;
        this.peopleRepository = peopleRepository;
        this.recordsRepository = recordsRepository;
        this.workspaceAccess = workspaceAccess;
        this.roomIdGenerator = roomIdGenerator;
        this.membershipVerifier = membershipVerifier;
        this.clock = clock;
        this.identities = identities;
    }

    @Override
    public Optional<MembershipResult> findCurrentMembership(CurrentMembershipQuery query) {
        workspaceAccess.verifyTeamRead(query.teamId(), query.workspaceAccessKey());
        return roundRepository.findMembership(query.accountId(), query.teamId())
                .map(this::membershipResult);
    }

    @Override
    public List<RoomMappingResult> findCurrentRoomMappings(CurrentRoomMappingsQuery query) {
        workspaceAccess.verifyTeamRead(query.teamId(), query.workspaceAccessKey());
        membershipVerifier.requireActive(query.accountId(), query.teamId());
        return roundRepository.findMappingsByTeamIdAndSeasonId(
                        query.teamId(),
                        query.seasonId()
                ).stream()
                .map(mapping -> mappingResult(mapping, null))
                .toList();
    }

    @Override
    @Transactional
    public MembershipResult claimMembership(ClaimMembershipCommand command) {
        workspaceAccess.verifyMembershipClaim(
                command.teamId(),
                command.seasonId(),
                command.workspaceAccessKey()
        );
        identities.findAccountById(command.accountId()).filter(Account::isActive).orElseThrow(AccountDeactivatedException::new);
        Member member = peopleRepository.findMemberById(command.memberId())
                .filter(found -> found.getTeamId().equals(command.teamId()))
                .filter(Member::isActive)
                .orElseThrow(() -> new AccountMembershipConflictException(
                        "연결할 수 있는 활성 구성원을 찾을 수 없습니다"
                ));

        AccountTeamMembership existing = roundRepository
                .findMembership(command.accountId(), command.teamId())
                .orElse(null);
        if (existing != null) {
            if (!existing.getMemberId().equals(member.getId())) {
                throw new AccountMembershipConflictException(
                        "이 계정은 팀의 다른 구성원과 이미 연결되어 있습니다"
                );
            }
            return membershipResult(existing);
        }
        MembershipClaimResult claimResult = roundRepository.claimMembership(
                AccountTeamMembership.create(
                        UUID.randomUUID(),
                        command.accountId(),
                        command.teamId(),
                        member.getId(),
                        clock.instant()
                )
        );
        return switch (claimResult) {
            case MembershipClaimResult.Claimed result -> membershipResult(result.membership());
            case MembershipClaimResult.AccountTeamAlreadyClaimed result -> {
                if (!result.membership().getMemberId().equals(member.getId())) {
                    throw new AccountMembershipConflictException(
                            "이 계정은 팀의 다른 구성원과 이미 연결되어 있습니다"
                    );
                }
                yield membershipResult(result.membership());
            }
            case MembershipClaimResult.MemberAlreadyClaimed ignored ->
                    throw new AccountMembershipConflictException(
                            "이 구성원은 다른 계정과 이미 연결되어 있습니다"
                    );
        };
    }

    @Override
    @Transactional
    public RoomMappingResult createRoomMapping(CreateRoomMappingCommand command) {
        workspaceAccess.verifyMutation(
                command.teamId(),
                command.seasonId(),
                command.workspaceAccessKey()
        );
        membershipVerifier.requireActive(command.accountId(), command.teamId());
        requireResource(command.teamId(), command.seasonId(), command.resourceId());

        RoundRoomMapping existing = roundRepository
                .findMappingByResourceId(command.resourceId())
                .orElse(null);
        if (existing != null) {
            return mappingResult(existing, null);
        }

        Instant createdAt = clock.instant();
        for (int attempt = 0; attempt < ROOM_ID_GENERATION_ATTEMPTS; attempt++) {
            String roomId = roomIdGenerator.generate();
            RoundRoomTombstone tombstone = RoundRoomTombstone.create(
                    roomId,
                    command.teamId(),
                    command.seasonId(),
                    command.resourceId(),
                    createdAt
            );
            RoundRoomMapping mapping = RoundRoomMapping.create(
                    UUID.randomUUID(),
                    roomId,
                    command.teamId(),
                    command.seasonId(),
                    command.resourceId(),
                    createdAt
            );
            RoomMappingCreationResult result = roundRepository.createMapping(tombstone, mapping);
            switch (result) {
                case RoomMappingCreationResult.Created created -> {
                    return mappingResult(created.mapping(), null);
                }
                case RoomMappingCreationResult.ResourceAlreadyMapped concurrent -> {
                    return mappingResult(concurrent.mapping(), null);
                }
                case RoomMappingCreationResult.RoomIdUnavailable ignored -> {
                    continue;
                }
                case RoomMappingCreationResult.ResourceBecameAvailable ignored -> {
                    continue;
                }
            }
        }
        throw new RoundRoomConflictException("고유한 ROUND 방 식별자를 만들지 못했습니다");
    }

    @Override
    @Transactional
    public RoomMappingResult endRoomMapping(EndRoomMappingCommand command) {
        RoundRoomId roomId = new RoundRoomId(command.roomId());
        RoundRoomTombstone tombstone = roundRepository.findTombstoneForUpdate(roomId.value())
                .orElseThrow(RoundRoomNotFoundException::new);
        workspaceAccess.verifyMutation(
                tombstone.getTeamId(),
                tombstone.getSeasonId(),
                command.workspaceAccessKey()
        );
        membershipVerifier.requireActive(command.accountId(), tombstone.getTeamId());

        if (tombstone.isEnded()) {
            return mappingResult(tombstone);
        }

        RoundRoomMapping mapping = roundRepository.findMappingByRoomId(roomId.value())
                .orElseThrow(RoundRoomNotFoundException::new);
        tombstone.end(clock.instant());
        roundRepository.saveTombstone(tombstone);
        roundRepository.deleteMapping(mapping);
        return mappingResult(tombstone);
    }

    private void requireResource(UUID teamId, UUID seasonId, UUID resourceId) {
        RoleResource resource = recordsRepository.findRoleResourceById(resourceId)
                .orElseThrow(() -> new RoundRoomConflictException(
                        "ROUND 방에 연결할 자료를 찾을 수 없습니다"
                ));
        if (resource.getArchivedAt() != null) {
            throw new RoundRoomConflictException("보관한 역할 자료에는 ROUND 방을 연결할 수 없습니다");
        }
        peopleRepository.findRoleById(resource.getRoleId())
                .filter(found -> found.getTeamId().equals(teamId))
                .filter(found -> found.getSeasonId().equals(seasonId))
                .orElseThrow(() -> new RoundRoomConflictException(
                        "ROUND 자료가 요청한 팀과 시즌에 속하지 않습니다"
                ));
    }

    private MembershipResult membershipResult(AccountTeamMembership membership) {
        return new MembershipResult(
                membership.getAccountId(),
                membership.getTeamId(),
                membership.getMemberId(),
                membership.getClaimedAt()
        );
    }

    private RoomMappingResult mappingResult(RoundRoomMapping mapping, Instant endedAt) {
        return new RoomMappingResult(
                mapping.getRoomId(),
                mapping.getTeamId(),
                mapping.getSeasonId(),
                mapping.getResourceId(),
                mapping.getCreatedAt(),
                endedAt
        );
    }

    private RoomMappingResult mappingResult(RoundRoomTombstone tombstone) {
        return new RoomMappingResult(
                tombstone.getRoomId(),
                tombstone.getTeamId(),
                tombstone.getSeasonId(),
                tombstone.getResourceId(),
                tombstone.getCreatedAt(),
                tombstone.getEndedAt()
        );
    }
}
