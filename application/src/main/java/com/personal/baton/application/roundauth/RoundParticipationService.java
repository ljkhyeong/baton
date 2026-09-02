package com.personal.baton.application.roundauth;

import com.personal.baton.application.roundauth.error.RoundParticipationDeniedException;
import com.personal.baton.application.roundauth.error.RoundRoomNotFoundException;
import com.personal.baton.application.roundauth.port.in.RoundParticipationUseCase;
import com.personal.baton.application.roundauth.port.out.ParticipationGrantJwkSetProvider;
import com.personal.baton.application.roundauth.port.out.ParticipationGrantSigner;
import com.personal.baton.application.roundauth.port.out.ParticipationGrantSigner.ParticipationGrantClaims;
import com.personal.baton.application.roundauth.port.out.RoundAuthorizationRepository;
import com.personal.baton.application.workspace.port.out.WorkspaceRecordsRepository;
import com.personal.baton.application.workspace.port.out.WorkspaceSeasonRepository;
import com.personal.baton.domain.roundauth.RoundRoomId;
import com.personal.baton.domain.roundauth.RoundRoomMapping;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class RoundParticipationService implements RoundParticipationUseCase {

    static final int GRANT_LIFETIME_SECONDS = 300;
    static final int REFRESH_AFTER_SECONDS = 240;

    private final RoundAuthorizationRepository roundRepository;
    private final WorkspaceRecordsRepository recordsRepository;
    private final WorkspaceSeasonRepository seasonRepository;
    private final RoundMembershipVerifier membershipVerifier;
    private final ParticipationGrantSigner grantSigner;
    private final ParticipationGrantJwkSetProvider jwkSetProvider;
    private final Clock clock;

    public RoundParticipationService(
            RoundAuthorizationRepository roundRepository,
            WorkspaceRecordsRepository recordsRepository,
            WorkspaceSeasonRepository seasonRepository,
            RoundMembershipVerifier membershipVerifier,
            ParticipationGrantSigner grantSigner,
            ParticipationGrantJwkSetProvider jwkSetProvider,
            Clock clock
    ) {
        this.roundRepository = roundRepository;
        this.recordsRepository = recordsRepository;
        this.seasonRepository = seasonRepository;
        this.membershipVerifier = membershipVerifier;
        this.grantSigner = grantSigner;
        this.jwkSetProvider = jwkSetProvider;
        this.clock = clock;
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
        requireActiveMappedResource(mapping.getResourceId());
        membershipVerifier.requireActive(command.accountId(), mapping.getTeamId());
        requireActiveSeason(mapping.getTeamId(), mapping.getSeasonId());
        requireHintMatches(command.hint(), mapping);

        Instant issuedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
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

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public String readPublicJwkSetJson() {
        return jwkSetProvider.readPublicJwkSetJson();
    }

    private void requireActiveMappedResource(UUID resourceId) {
        recordsRepository.findRoleResourceById(resourceId)
                .filter(resource -> resource.getArchivedAt() == null)
                .orElseThrow(RoundRoomNotFoundException::new);
    }

    private void requireActiveSeason(UUID teamId, UUID seasonId) {
        seasonRepository.findSeasonById(seasonId)
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
}
