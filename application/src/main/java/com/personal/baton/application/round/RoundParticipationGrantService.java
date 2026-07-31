package com.personal.baton.application.round;

import com.personal.baton.application.identity.port.in.MemberIdentityUseCase.AuthenticatedAccount;
import com.personal.baton.application.round.error.RoundGrantOperationException;
import com.personal.baton.application.round.port.in.RoundParticipationGrantUseCase;
import com.personal.baton.application.round.port.out.RoundGrantResourceRepository;
import com.personal.baton.application.round.port.out.RoundGrantResourceRepository.AuthorizedRoundResource;
import com.personal.baton.application.round.port.out.RoundParticipationGrantPort;
import com.personal.baton.application.round.port.out.RoundParticipationGrantPort.ParticipantGrantCommand;
import com.personal.baton.application.workspace.error.WorkspaceAccessDeniedException;
import com.personal.baton.application.workspace.error.WorkspaceNotFoundException;
import com.personal.baton.application.workspace.port.in.WorkspaceAuthorization.SessionAccount;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.RoleResourceResult;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RoundParticipationGrantService implements RoundParticipationGrantUseCase {

    static final String FORBIDDEN = "ROUND_GRANT_FORBIDDEN";
    static final String RESOURCE_NOT_FOUND = "ROUND_RESOURCE_NOT_FOUND";
    static final String RESOURCE_AMBIGUOUS = "ROUND_RESOURCE_AMBIGUOUS";
    static final String SIGNER_UNAVAILABLE = "ROUND_GRANT_SIGNER_UNAVAILABLE";
    private static final Duration MAXIMUM_GRANT_LIFETIME = Duration.ofMinutes(5);
    private static final Pattern CANONICAL_ROOM_ID = Pattern.compile(
            "^[abcdefghjkmnpqrstuvwxyz23456789]{4}"
                    + "(?:-[abcdefghjkmnpqrstuvwxyz23456789]{4}){2}$"
    );
    private static final Pattern COMPACT_JWS = Pattern.compile(
            "^[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+$"
    );

    private final WorkspaceUseCase workspaceUseCase;
    private final RoundGrantResourceRepository grantResourceRepository;
    private final RoundParticipationGrantPort grantPort;
    private final Clock clock;

    public RoundParticipationGrantService(
            WorkspaceUseCase workspaceUseCase,
            RoundGrantResourceRepository grantResourceRepository,
            RoundParticipationGrantPort grantPort,
            Clock clock
    ) {
        this.workspaceUseCase = workspaceUseCase;
        this.grantResourceRepository = grantResourceRepository;
        this.grantPort = grantPort;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public IssuedRoundParticipationGrant issue(
            UUID teamId,
            UUID seasonId,
            UUID resourceId,
            AuthenticatedAccount authenticatedAccount
    ) {
        RoleResourceResult resource = authorizeDirectResource(
                teamId,
                seasonId,
                resourceId,
                authenticatedAccount
        );

        Instant issuedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        RoundParticipationGrantPort.SignedParticipationGrant signed =
                grantPort.issueParticipantGrant(new ParticipantGrantCommand(
                        authenticatedAccount.accountId(),
                        seasonId,
                        resource.url(),
                        issuedAt
                ));
        return requireValidSignedGrant(signed, issuedAt, null);
    }

    @Override
    @Transactional(readOnly = true)
    public IssuedRoundParticipationGrant issueForRoom(
            String roomId,
            AuthenticatedAccount authenticatedAccount
    ) {
        String canonicalResourceUrl = grantPort.canonicalResourceUrl(roomId);
        List<AuthorizedRoundResource> candidates = grantResourceRepository
                .findAuthorizedResourcesByAccountIdAndUrl(
                        authenticatedAccount.accountId(),
                        canonicalResourceUrl
                )
                .stream()
                .filter(candidate -> candidate.resourceUrl().equals(canonicalResourceUrl))
                .toList();
        if (candidates.isEmpty()) {
            throw new RoundGrantOperationException(
                    FORBIDDEN,
                    "현재 계정으로 이 ROUND room에 참여할 수 없습니다"
            );
        }
        if (candidates.size() != 1) {
            throw new RoundGrantOperationException(
                    RESOURCE_AMBIGUOUS,
                    "ROUND room에 연결된 역할 자료를 하나로 결정할 수 없습니다"
            );
        }
        AuthorizedRoundResource candidate = candidates.getFirst();
        RoleResourceResult resource = authorizeRoomResource(
                candidate,
                authenticatedAccount,
                canonicalResourceUrl
        );
        Instant issuedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        RoundParticipationGrantPort.SignedParticipationGrant signed =
                grantPort.issueParticipantGrant(new ParticipantGrantCommand(
                        authenticatedAccount.accountId(),
                        candidate.seasonId(),
                        resource.url(),
                        issuedAt
                ));
        return requireValidSignedGrant(signed, issuedAt, roomId);
    }

    private RoleResourceResult authorizeDirectResource(
            UUID teamId,
            UUID seasonId,
            UUID resourceId,
            AuthenticatedAccount authenticatedAccount
    ) {
        try {
            return workspaceUseCase.getRoleResourceForGrantAuthorized(
                    teamId,
                    seasonId,
                    resourceId,
                    new SessionAccount(authenticatedAccount)
            );
        } catch (WorkspaceAccessDeniedException exception) {
            throw directForbidden();
        } catch (WorkspaceNotFoundException exception) {
            throw resourceNotFound();
        }
    }

    private RoleResourceResult authorizeRoomResource(
            AuthorizedRoundResource candidate,
            AuthenticatedAccount authenticatedAccount,
            String canonicalResourceUrl
    ) {
        try {
            RoleResourceResult resource = workspaceUseCase.getRoleResourceForGrantAuthorized(
                    candidate.teamId(),
                    candidate.seasonId(),
                    candidate.resourceId(),
                    new SessionAccount(authenticatedAccount)
            );
            if (!resource.url().equals(canonicalResourceUrl)) {
                throw forbidden();
            }
            return resource;
        } catch (WorkspaceAccessDeniedException | WorkspaceNotFoundException exception) {
            throw forbidden();
        }
    }

    @Override
    public PublicRoundJwkSet getPublicJwkSet() {
        RoundParticipationGrantPort.PublicJwkSet jwkSet = grantPort.loadPublicJwkSet();
        if (jwkSet == null
                || jwkSet.body().isBlank()
                || jwkSet.etag().isBlank()
                || jwkSet.etag().contains("\"")
                || jwkSet.etag().contains("\r")
                || jwkSet.etag().contains("\n")) {
            throw signerUnavailable();
        }
        return new PublicRoundJwkSet(jwkSet.body(), jwkSet.etag());
    }

    private IssuedRoundParticipationGrant requireValidSignedGrant(
            RoundParticipationGrantPort.SignedParticipationGrant signed,
            Instant expectedIssuedAt,
            String expectedRoomId
    ) {
        if (signed == null
                || !signed.issuedAt().equals(expectedIssuedAt)
                || !signed.expiresAt().isAfter(signed.issuedAt())
                || Duration.between(signed.issuedAt(), signed.expiresAt())
                        .compareTo(MAXIMUM_GRANT_LIFETIME) > 0
                || !CANONICAL_ROOM_ID.matcher(signed.roomId()).matches()
                || (expectedRoomId != null && !expectedRoomId.equals(signed.roomId()))
                || signed.token().length() > 8192
                || !COMPACT_JWS.matcher(signed.token()).matches()) {
            throw signerUnavailable();
        }
        long maxAgeSeconds = Duration.between(
                signed.issuedAt(),
                signed.expiresAt()
        ).toSeconds();
        if (maxAgeSeconds <= 0 || maxAgeSeconds > MAXIMUM_GRANT_LIFETIME.toSeconds()) {
            throw signerUnavailable();
        }
        return new IssuedRoundParticipationGrant(
                signed.token(),
                signed.roomId(),
                signed.issuedAt(),
                signed.expiresAt(),
                maxAgeSeconds
        );
    }

    private RoundGrantOperationException resourceNotFound() {
        return new RoundGrantOperationException(
                RESOURCE_NOT_FOUND,
                "요청한 ROUND 역할 자료를 찾을 수 없습니다"
        );
    }

    private RoundGrantOperationException directForbidden() {
        return new RoundGrantOperationException(
                FORBIDDEN,
                "현재 팀의 활성 구성원만 ROUND에 참여할 수 있습니다"
        );
    }

    private RoundGrantOperationException forbidden() {
        return new RoundGrantOperationException(
                FORBIDDEN,
                "현재 계정으로 이 ROUND room에 참여할 수 없습니다"
        );
    }

    private RoundGrantOperationException signerUnavailable() {
        return new RoundGrantOperationException(
                SIGNER_UNAVAILABLE,
                "ROUND 참여권 서명기를 사용할 수 없습니다"
        );
    }
}
