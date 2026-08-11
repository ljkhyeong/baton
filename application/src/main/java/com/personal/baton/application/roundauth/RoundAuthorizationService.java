package com.personal.baton.application.roundauth;

import com.personal.baton.application.roundauth.error.AccountMembershipConflictException;
import com.personal.baton.application.roundauth.error.RoundParticipationDeniedException;
import com.personal.baton.application.roundauth.error.RoundRoomConflictException;
import com.personal.baton.application.roundauth.error.RoundRoomNotFoundException;
import com.personal.baton.application.roundauth.port.in.RoundAuthorizationUseCase;
import com.personal.baton.application.roundauth.port.out.ParticipationGrantSigner;
import com.personal.baton.application.roundauth.port.out.ParticipationGrantSigner.ParticipationGrantClaims;
import com.personal.baton.application.roundauth.port.out.RoundAuthorizationRepository;
import com.personal.baton.application.roundauth.port.out.RoundAuthorizationRepository.MembershipClaimResult;
import com.personal.baton.application.roundauth.port.out.RoundAuthorizationRepository.RoomMappingCreationResult;
import com.personal.baton.application.roundauth.port.out.RoundRoomIdGenerator;
import com.personal.baton.application.workspace.port.in.VerifyWorkspaceAccessUseCase;
import com.personal.baton.application.workspace.port.out.WorkspaceRepository;
import com.personal.baton.domain.roundauth.AccountTeamMembership;
import com.personal.baton.domain.roundauth.RoundRoomId;
import com.personal.baton.domain.roundauth.RoundRoomMapping;
import com.personal.baton.domain.roundauth.RoundRoomTombstone;
import com.personal.baton.domain.workspace.Member;
import com.personal.baton.domain.workspace.Role;
import com.personal.baton.domain.workspace.RoleResource;
import com.personal.baton.domain.workspace.Season;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class RoundAuthorizationService implements RoundAuthorizationUseCase {

    static final int GRANT_LIFETIME_SECONDS = 300;
    static final int REFRESH_AFTER_SECONDS = 240;
    private static final int ROOM_ID_GENERATION_ATTEMPTS = 8;

    private final RoundAuthorizationRepository roundRepository;
    private final WorkspaceRepository workspaceRepository;
    private final VerifyWorkspaceAccessUseCase workspaceAccess;
    private final RoundRoomIdGenerator roomIdGenerator;
    private final ParticipationGrantSigner grantSigner;
    private final Clock clock;

    public RoundAuthorizationService(
            RoundAuthorizationRepository roundRepository,
            WorkspaceRepository workspaceRepository,
            VerifyWorkspaceAccessUseCase workspaceAccess,
            RoundRoomIdGenerator roomIdGenerator,
            ParticipationGrantSigner grantSigner,
            Clock clock
    ) {
        this.roundRepository = roundRepository;
        this.workspaceRepository = workspaceRepository;
        this.workspaceAccess = workspaceAccess;
        this.roomIdGenerator = roomIdGenerator;
        this.grantSigner = grantSigner;
        this.clock = clock;
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
        requireActiveMembership(query.accountId(), query.teamId());
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
        workspaceAccess.verifyMutation(
                command.teamId(),
                command.seasonId(),
                command.workspaceAccessKey()
        );
        Member member = workspaceRepository.findMemberById(command.memberId())
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
        roundRepository.findMembershipByMemberId(member.getId()).ifPresent(claimed -> {
            throw new AccountMembershipConflictException(
                    "이 구성원은 다른 계정과 이미 연결되어 있습니다"
            );
        });

        MembershipClaimResult claimResult = roundRepository.claimMembership(
                AccountTeamMembership.create(
                        UUID.randomUUID(),
                        command.accountId(),
                        command.teamId(),
                        member.getId(),
                        clock.instant()
                )
        );
        AccountTeamMembership claimed = switch (claimResult) {
            case MembershipClaimResult.Claimed result -> result.membership();
            case MembershipClaimResult.AlreadyClaimed result -> result.membership();
        };
        if (claimed.getAccountId().equals(command.accountId())
                && claimed.getTeamId().equals(command.teamId())
                && claimed.getMemberId().equals(member.getId())) {
            return membershipResult(claimed);
        }
        if (claimed.getAccountId().equals(command.accountId())
                && claimed.getTeamId().equals(command.teamId())) {
            throw new AccountMembershipConflictException(
                    "이 계정은 팀의 다른 구성원과 이미 연결되어 있습니다"
            );
        }
        if (claimed.getMemberId().equals(member.getId())) {
            throw new AccountMembershipConflictException(
                    "이 구성원은 다른 계정과 이미 연결되어 있습니다"
            );
        }
        throw new IllegalStateException("계정 멤버십 경쟁 결과가 요청 범위와 일치하지 않습니다");
    }

    @Override
    @Transactional
    public RoomMappingResult createRoomMapping(CreateRoomMappingCommand command) {
        workspaceAccess.verifyMutation(
                command.teamId(),
                command.seasonId(),
                command.workspaceAccessKey()
        );
        requireActiveMembership(command.accountId(), command.teamId());
        requireResource(command.teamId(), command.seasonId(), command.resourceId());

        RoundRoomMapping existing = roundRepository
                .findMappingByResourceId(command.resourceId())
                .orElse(null);
        if (existing != null) {
            return mappingResult(existing, null);
        }

        Instant createdAt = clock.instant();
        for (int attempt = 0; attempt < ROOM_ID_GENERATION_ATTEMPTS; attempt++) {
            String roomId = new RoundRoomId(roomIdGenerator.generate()).value();
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
            RoomMappingCreationResult result = roundRepository.createMapping(
                    tombstone,
                    mapping
            );
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
        requireActiveMembership(command.accountId(), tombstone.getTeamId());

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

    @Override
    public ParticipationGrantResult issueParticipationGrant(
            IssueParticipationGrantCommand command
    ) {
        RoundRoomId roomId = new RoundRoomId(command.roomId());
        roundRepository.findTombstoneForShare(roomId.value())
                .filter(found -> !found.isEnded())
                .orElseThrow(RoundRoomNotFoundException::new);
        RoundRoomMapping mapping = roundRepository.findMappingByRoomId(roomId.value())
                .orElseThrow(RoundRoomNotFoundException::new);
        requireActiveMembership(command.accountId(), mapping.getTeamId());
        requireActiveSeason(mapping.getTeamId(), mapping.getSeasonId());
        requireHintMatches(command.hint(), mapping);

        Instant issuedAt = Instant.ofEpochSecond(clock.instant().getEpochSecond());
        Instant expiresAt = issuedAt.plusSeconds(GRANT_LIFETIME_SECONDS);
        String token = grantSigner.sign(new ParticipationGrantClaims(
                command.accountId(),
                mapping.getTeamId(),
                roomId.value(),
                UUID.randomUUID(),
                "participant",
                issuedAt,
                expiresAt
        ));
        return new ParticipationGrantResult(
                token,
                expiresAt.getEpochSecond(),
                REFRESH_AFTER_SECONDS,
                roomId.value()
        );
    }

    private void requireResource(UUID teamId, UUID seasonId, UUID resourceId) {
        RoleResource resource = workspaceRepository.findRoleResourceById(resourceId)
                .orElseThrow(() -> new RoundRoomConflictException(
                        "ROUND 방에 연결할 자료를 찾을 수 없습니다"
                ));
        Role role = workspaceRepository.findRoleById(resource.getRoleId())
                .filter(found -> found.getTeamId().equals(teamId))
                .filter(found -> found.getSeasonId().equals(seasonId))
                .orElseThrow(() -> new RoundRoomConflictException(
                        "ROUND 자료가 요청한 팀과 시즌에 속하지 않습니다"
                ));
    }

    private void requireActiveMembership(UUID accountId, UUID teamId) {
        AccountTeamMembership membership = roundRepository
                .findMembership(accountId, teamId)
                .orElseThrow(RoundParticipationDeniedException::new);
        boolean activeMember = workspaceRepository.findMemberById(membership.getMemberId())
                .filter(member -> member.getTeamId().equals(teamId))
                .filter(Member::isActive)
                .isPresent();
        if (!activeMember) {
            throw new RoundParticipationDeniedException();
        }
    }

    private void requireActiveSeason(UUID teamId, UUID seasonId) {
        Season season = workspaceRepository.findSeasonById(seasonId)
                .filter(found -> found.getTeamId().equals(teamId))
                .filter(found -> !found.isEnded())
                .orElseThrow(RoundParticipationDeniedException::new);
    }

    private void requireHintMatches(RoundRoomHint hint, RoundRoomMapping mapping) {
        if (hint == null) {
            return;
        }
        if (!mapping.getTeamId().equals(hint.teamId())
                || !mapping.getSeasonId().equals(hint.seasonId())
                || !mapping.getResourceId().equals(hint.resourceId())) {
            throw new RoundRoomNotFoundException();
        }
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
