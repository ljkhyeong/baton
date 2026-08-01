package com.personal.baton.application.identity;

import com.personal.baton.application.identity.error.IdentityNotFoundException;
import com.personal.baton.application.identity.error.IdentityOperationException;
import com.personal.baton.application.identity.error.MemberIdentityConflictException;
import com.personal.baton.application.identity.port.in.MemberIdentityUseCase.AuthenticatedAccount;
import com.personal.baton.application.identity.port.in.MemberInvitationUseCase;
import com.personal.baton.application.identity.port.out.IdentityRepository;
import com.personal.baton.application.identity.port.out.MemberInvitationRepository;
import com.personal.baton.application.identity.port.out.MemberInvitationRepository.InvitationInsertResult;
import com.personal.baton.application.identity.port.out.MemberInvitationRepository.InvitationObservation;
import com.personal.baton.domain.identity.MemberIdentityBinding;
import com.personal.baton.domain.identity.MemberIdentityRole;
import com.personal.baton.domain.identity.MemberInvitation;
import com.personal.baton.domain.identity.MemberInvitation.Acceptance;
import com.personal.baton.domain.identity.MemberInvitationStateException;
import com.personal.baton.domain.workspace.Member;
import com.personal.baton.domain.workspace.Team;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MemberInvitationService implements MemberInvitationUseCase {

    static final String TOKEN_PREFIX = "mi1_";

    private static final String IDEMPOTENCY_HASH_DOMAIN =
            "baton:member-invitation-idempotency:v1";
    private static final String TOKEN_DERIVATION_DOMAIN =
            "baton:member-invitation-token:v1";
    private static final Duration MAXIMUM_TTL = Duration.ofDays(7);
    private static final int MINIMUM_SECRET_LENGTH = 32;

    private final IdentityRepository identityRepository;
    private final MemberInvitationRepository invitationRepository;
    private final Clock clock;
    private final String invitationHmacSecret;
    private final Duration invitationTtl;

    public MemberInvitationService(
            IdentityRepository identityRepository,
            MemberInvitationRepository invitationRepository,
            Clock clock,
            IdentityInvitationSettings settings
    ) {
        this.identityRepository = identityRepository;
        this.invitationRepository = invitationRepository;
        this.clock = clock;
        this.invitationHmacSecret = settings.invitationHmacSecret();
        this.invitationTtl = settings.memberInvitationTtl();
    }

    @Override
    @Transactional
    public IssuedMemberInvitation issue(
            AuthenticatedAccount authenticatedAccount,
            String idempotencyKey,
            IssueMemberInvitationCommand command
    ) {
        if (command == null) {
            throw invalidInput("구성원 초대 대상은 필수입니다");
        }
        UUID accountId = requireAuthenticatedAccount(authenticatedAccount);
        identityRepository.findUserAccountByIdForUpdate(accountId)
                .orElseThrow(this::accountNotFound);
        MemberIdentityBinding ownerBinding = requireOwner(command.teamId(), accountId);
        LockedInvitationMembers lockedMembers = lockInvitationMembers(
                command.teamId(),
                ownerBinding.getMemberId(),
                command.memberId()
        );
        if (!lockedMembers.issuer().isActive()) {
            throw forbidden();
        }

        UUID canonicalIdempotencyKey = parseCanonicalIdempotencyKey(idempotencyKey);
        verifyConfiguration();
        String canonicalKey = canonicalIdempotencyKey.toString();
        String token = deriveToken(canonicalKey);
        String idempotencyKeyHash = IdentityCrypto.sha256Hex(
                IDEMPOTENCY_HASH_DOMAIN,
                canonicalKey
        );
        String tokenHash = IdentityCrypto.sha256HexOfToken(token);

        Optional<MemberInvitation> existing =
                invitationRepository.findByIdempotencyKeyHash(idempotencyKeyHash);
        if (existing.isPresent()) {
            return replayIssue(
                    existing.orElseThrow(),
                    command,
                    accountId,
                    idempotencyKeyHash,
                    tokenHash,
                    token,
                    true
            );
        }

        Member target = lockedMembers.target();
        if (!target.isActive()
                || identityRepository.findBindingByMemberId(target.getId()).isPresent()
                || invitationRepository.findOpenByTeamIdAndMemberIdForUpdate(
                        command.teamId(),
                        command.memberId(),
                        clock.instant()
                ).isPresent()) {
            throw targetUnavailable();
        }

        Instant issuedAt = clock.instant();
        MemberInvitation candidate = MemberInvitation.issue(
                UUID.randomUUID(),
                command.teamId(),
                command.memberId(),
                accountId,
                idempotencyKeyHash,
                tokenHash,
                issuedAt,
                issuedAt.plus(invitationTtl)
        );
        InvitationInsertResult insertion = invitationRepository.insertIfAbsent(candidate);
        return replayIssue(
                insertion.invitation(),
                command,
                accountId,
                idempotencyKeyHash,
                tokenHash,
                token,
                !insertion.created()
        );
    }

    @Override
    @Transactional(readOnly = true)
    public List<OpenMemberInvitation> listOpen(
            UUID teamId,
            AuthenticatedAccount authenticatedAccount
    ) {
        UUID accountId = requireAuthenticatedAccount(authenticatedAccount);
        requireTeamId(teamId);
        identityRepository.findUserAccountById(accountId)
                .orElseThrow(this::accountNotFound);
        requireActiveOwner(teamId, accountId);
        return invitationRepository.findAllOpenByTeamId(teamId, clock.instant())
                .stream()
                .map(invitation -> new OpenMemberInvitation(
                        invitation.getId(),
                        invitation.getTeamId(),
                        invitation.getMemberId(),
                        invitation.getIssuedAt(),
                        invitation.getExpiresAt()
                ))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public PreviewedMemberInvitation preview(
            String token,
            AuthenticatedAccount authenticatedAccount
    ) {
        UUID accountId = requireAuthenticatedAccount(authenticatedAccount);
        identityRepository.findUserAccountById(accountId)
                .orElseThrow(this::accountNotFound);
        MemberInvitation invitation = invitationRepository.findByTokenHash(
                        acceptedTokenHash(token)
                )
                .orElseThrow(this::invitationNotFound);
        boolean alreadyAccepted;
        try {
            alreadyAccepted = invitation.inspect(accountId, clock.instant())
                    == MemberInvitation.Availability.REPLAY;
        } catch (MemberInvitationStateException exception) {
            throw translateState(exception);
        }
        Team team = identityRepository.findTeamById(invitation.getTeamId())
                .orElseThrow(() -> new IdentityNotFoundException(
                        "TEAM_NOT_FOUND",
                        "팀을 찾을 수 없습니다"
                ));
        Member member = identityRepository.findMemberByTeamIdAndId(
                        invitation.getTeamId(),
                        invitation.getMemberId()
                )
                .orElseThrow(() -> new IdentityNotFoundException(
                        "MEMBER_NOT_FOUND",
                        "구성원을 찾을 수 없습니다"
                ));
        if (alreadyAccepted) {
            identityRepository.findBindingByMemberId(invitation.getMemberId())
                    .filter(binding -> binding.belongsTo(accountId))
                    .orElseThrow(() -> new IllegalStateException(
                            "소비된 구성원 초대의 신원 결속을 찾을 수 없습니다"
                    ));
        } else {
            requireActiveIssuer(invitation);
            if (!member.isActive()
                    || identityRepository.findBindingByMemberId(member.getId()).isPresent()
                    || identityRepository.findBindingByTeamIdAndUserAccountId(
                            invitation.getTeamId(),
                            accountId
                    ).isPresent()) {
                throw targetUnavailable();
            }
        }
        return new PreviewedMemberInvitation(
                invitation.getTeamId(),
                team.getName(),
                invitation.getMemberId(),
                member.getName(),
                invitation.getExpiresAt(),
                alreadyAccepted
        );
    }

    @Override
    @Transactional
    public AcceptedMemberInvitation accept(
            String token,
            AuthenticatedAccount authenticatedAccount
    ) {
        UUID accountId = requireAuthenticatedAccount(authenticatedAccount);
        String tokenHash = acceptedTokenHash(token);
        identityRepository.findUserAccountByIdForUpdate(accountId)
                .orElseThrow(this::accountNotFound);
        InvitationObservation observed = invitationRepository.findObservationByTokenHash(tokenHash)
                .orElseThrow(this::invitationNotFound);
        Instant observedAt = clock.instant();
        if (!observed.isOpenAt(observedAt)) {
            MemberInvitation terminal = invitationRepository.findByTokenHashForUpdate(
                            tokenHash
                    )
                    .orElseThrow(this::invitationNotFound);
            Acceptance terminalAcceptance;
            try {
                terminalAcceptance = terminal.consume(accountId, clock.instant());
            } catch (MemberInvitationStateException exception) {
                throw translateState(exception);
            }
            if (terminalAcceptance != Acceptance.REPLAY) {
                throw new IllegalStateException("열리지 않은 구성원 초대가 새로 소비되었습니다");
            }
            return acceptedReplay(terminal, accountId);
        }
        MemberIdentityBinding observedIssuer = requireIssuer(
                observed.teamId(),
                observed.issuedByAccountId()
        );
        LockedInvitationMembers lockedMembers = lockInvitationMembers(
                observed.teamId(),
                observedIssuer.getMemberId(),
                observed.memberId()
        );
        MemberInvitation invitation = invitationRepository.findByTokenHashForUpdate(
                        tokenHash
                )
                .orElseThrow(this::invitationNotFound);
        if (!invitation.getId().equals(observed.invitationId())) {
            throw new IdentityOperationException(
                    "MEMBER_INVITATION_CONFLICT",
                    "구성원 초대 상태가 동시에 변경되었습니다"
            );
        }
        Instant acceptedAt = clock.instant();
        Acceptance acceptance;
        try {
            acceptance = invitation.consume(accountId, acceptedAt);
        } catch (MemberInvitationStateException exception) {
            throw translateState(exception);
        }
        if (acceptance == Acceptance.REPLAY) {
            return acceptedReplay(invitation, accountId);
        }

        MemberIdentityBinding issuer = requireIssuer(invitation);
        if (!issuer.getMemberId().equals(observedIssuer.getMemberId())
                || !lockedMembers.issuer().isActive()
                || !lockedMembers.target().isActive()) {
            throw targetUnavailable();
        }

        MemberIdentityBinding binding = memberBinding(invitation, accountId, acceptedAt);
        invitationRepository.save(invitation);
        return acceptedResult(invitation, binding);
    }

    @Override
    @Transactional
    public RevokedMemberInvitation revoke(
            UUID teamId,
            UUID invitationId,
            AuthenticatedAccount authenticatedAccount
    ) {
        UUID accountId = requireAuthenticatedAccount(authenticatedAccount);
        requireTeamId(teamId);
        if (invitationId == null) {
            throw invalidInput("구성원 초대 식별자는 필수입니다");
        }
        identityRepository.findUserAccountByIdForUpdate(accountId)
                .orElseThrow(this::accountNotFound);
        MemberIdentityBinding owner = requireOwner(teamId, accountId);
        identityRepository.findMemberByTeamIdAndIdForUpdate(
                        teamId,
                        owner.getMemberId()
                )
                .filter(Member::isActive)
                .orElseThrow(this::forbidden);
        MemberInvitation invitation = invitationRepository.findByTeamIdAndIdForUpdate(
                        teamId,
                        invitationId
                )
                .orElseThrow(this::invitationNotFound);
        try {
            invitation.revoke(accountId, clock.instant());
        } catch (MemberInvitationStateException exception) {
            throw translateState(exception);
        }
        MemberInvitation saved = invitationRepository.save(invitation);
        return new RevokedMemberInvitation(saved.getId(), saved.getRevokedAt());
    }

    private MemberIdentityBinding memberBinding(
            MemberInvitation invitation,
            UUID accountId,
            Instant now
    ) {
        Optional<MemberIdentityBinding> memberBinding =
                identityRepository.findBindingByMemberId(invitation.getMemberId());
        if (memberBinding.isPresent()
                && !memberBinding.orElseThrow().belongsTo(accountId)) {
            throw new MemberIdentityConflictException();
        }
        Optional<MemberIdentityBinding> accountBinding =
                identityRepository.findBindingByTeamIdAndUserAccountId(
                        invitation.getTeamId(),
                        accountId
                );
        if (accountBinding.isPresent()
                && !accountBinding.orElseThrow().getMemberId().equals(invitation.getMemberId())) {
            throw new MemberIdentityConflictException();
        }
        MemberIdentityBinding binding = memberBinding
                .or(() -> accountBinding)
                .orElseGet(() -> MemberIdentityBinding.bind(
                        invitation.getMemberId(),
                        invitation.getTeamId(),
                        accountId,
                        now,
                        MemberIdentityRole.MEMBER
                ));
        return identityRepository.saveBinding(binding);
    }

    private AcceptedMemberInvitation acceptedReplay(
            MemberInvitation invitation,
            UUID accountId
    ) {
        MemberIdentityBinding binding = identityRepository.findBindingByMemberId(
                        invitation.getMemberId()
                )
                .filter(found -> found.belongsTo(accountId))
                .orElseThrow(() -> new IllegalStateException(
                        "소비된 구성원 초대의 신원 결속을 찾을 수 없습니다"
                ));
        return acceptedResult(invitation, binding);
    }

    private AcceptedMemberInvitation acceptedResult(
            MemberInvitation invitation,
            MemberIdentityBinding binding
    ) {
        return new AcceptedMemberInvitation(
                invitation.getId(),
                binding.getUserAccountId(),
                binding.getTeamId(),
                binding.getMemberId(),
                binding.getBoundAt(),
                binding.getRole()
        );
    }

    private MemberIdentityBinding requireOwner(UUID teamId, UUID accountId) {
        requireTeamId(teamId);
        return identityRepository.findBindingByTeamIdAndUserAccountId(teamId, accountId)
                .filter(MemberIdentityBinding::isOwner)
                .orElseThrow(this::forbidden);
    }

    private MemberIdentityBinding requireActiveOwner(UUID teamId, UUID accountId) {
        MemberIdentityBinding owner = requireOwner(teamId, accountId);
        Member member = identityRepository.findMemberByTeamIdAndId(
                        teamId,
                        owner.getMemberId()
                )
                .filter(Member::isActive)
                .orElseThrow(this::forbidden);
        return owner;
    }

    private void requireActiveIssuer(MemberInvitation invitation) {
        MemberIdentityBinding issuer = requireIssuer(invitation);
        identityRepository.findMemberByTeamIdAndId(
                        invitation.getTeamId(),
                        issuer.getMemberId()
                )
                .filter(Member::isActive)
                .orElseThrow(this::targetUnavailable);
    }

    private MemberIdentityBinding requireIssuer(MemberInvitation invitation) {
        return requireIssuer(invitation.getTeamId(), invitation.getIssuedByAccountId());
    }

    private MemberIdentityBinding requireIssuer(UUID teamId, UUID issuedByAccountId) {
        return identityRepository.findBindingByTeamIdAndUserAccountId(
                        teamId,
                        issuedByAccountId
                )
                .filter(MemberIdentityBinding::isOwner)
                .orElseThrow(this::targetUnavailable);
    }

    private List<UUID> orderedDistinctIds(UUID first, UUID second) {
        return List.of(first, second)
                .stream()
                .distinct()
                .sorted((left, right) -> left.toString().compareTo(right.toString()))
                .toList();
    }

    private LockedInvitationMembers lockInvitationMembers(
            UUID teamId,
            UUID issuerMemberId,
            UUID targetMemberId
    ) {
        Member issuer = null;
        Member target = null;
        for (UUID memberId : orderedDistinctIds(issuerMemberId, targetMemberId)) {
            Member locked = identityRepository.findMemberByTeamIdAndIdForUpdate(
                            teamId,
                            memberId
                    )
                    .orElseThrow(() -> new IdentityNotFoundException(
                            "MEMBER_NOT_FOUND",
                            "구성원을 찾을 수 없습니다"
                    ));
            if (memberId.equals(issuerMemberId)) {
                issuer = locked;
            }
            if (memberId.equals(targetMemberId)) {
                target = locked;
            }
        }
        if (issuer == null || target == null) {
            throw new IllegalStateException("구성원 초대 잠금 대상을 찾을 수 없습니다");
        }
        return new LockedInvitationMembers(issuer, target);
    }

    private IssuedMemberInvitation replayIssue(
            MemberInvitation invitation,
            IssueMemberInvitationCommand command,
            UUID accountId,
            String idempotencyKeyHash,
            String tokenHash,
            String token,
            boolean replayed
    ) {
        if (!invitation.matchesCreation(
                command.teamId(),
                command.memberId(),
                accountId,
                idempotencyKeyHash,
                tokenHash
        )) {
            throw new IdentityOperationException(
                    "MEMBER_INVITATION_IDEMPOTENCY_KEY_REUSED",
                    "동일한 멱등 키를 다른 구성원 초대 요청에 사용할 수 없습니다"
            );
        }
        return new IssuedMemberInvitation(
                invitation.getId(),
                invitation.getTeamId(),
                invitation.getMemberId(),
                token,
                invitation.getIssuedAt(),
                invitation.getExpiresAt(),
                replayed
        );
    }

    private String deriveToken(String canonicalIdempotencyKey) {
        byte[] derived = IdentityCrypto.hmacSha256(
                invitationHmacSecret,
                TOKEN_DERIVATION_DOMAIN,
                canonicalIdempotencyKey
        );
        return TOKEN_PREFIX
                + Base64.getUrlEncoder().withoutPadding().encodeToString(derived);
    }

    private String acceptedTokenHash(String token) {
        if (!isMemberInvitationTokenShape(token)) {
            throw invitationNotFound();
        }
        String encoded = token.substring(TOKEN_PREFIX.length());
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(encoded);
            if (decoded.length != 32
                    || !Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(decoded)
                    .equals(encoded)) {
                throw invitationNotFound();
            }
        } catch (IllegalArgumentException exception) {
            throw invitationNotFound();
        }
        return IdentityCrypto.sha256HexOfToken(token);
    }

    static boolean isMemberInvitationTokenShape(String token) {
        return token != null
                && token.length() == TOKEN_PREFIX.length() + 43
                && token.startsWith(TOKEN_PREFIX);
    }

    private UUID parseCanonicalIdempotencyKey(String value) {
        if (value == null) {
            throw invalidIdempotencyKey();
        }
        try {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equals(value)) {
                throw invalidIdempotencyKey();
            }
            return parsed;
        } catch (IllegalArgumentException exception) {
            throw invalidIdempotencyKey();
        }
    }

    private UUID requireAuthenticatedAccount(AuthenticatedAccount account) {
        if (account == null) {
            throw invalidInput("인증 사용자 계정은 필수입니다");
        }
        return account.accountId();
    }

    private void requireTeamId(UUID teamId) {
        if (teamId == null) {
            throw invalidInput("팀 식별자는 필수입니다");
        }
    }

    private void verifyConfiguration() {
        if (invitationHmacSecret.length() < MINIMUM_SECRET_LENGTH
                || invitationTtl == null
                || invitationTtl.isZero()
                || invitationTtl.isNegative()
                || invitationTtl.compareTo(MAXIMUM_TTL) > 0) {
            throw new IdentityOperationException(
                    "MEMBER_INVITATION_CONFIGURATION_INVALID",
                    "구성원 초대 발급 설정이 안전하지 않습니다"
            );
        }
    }

    private IdentityOperationException translateState(
            MemberInvitationStateException exception
    ) {
        return switch (exception.getReason()) {
            case EXPIRED -> new IdentityOperationException(
                    "MEMBER_INVITATION_EXPIRED",
                    "구성원 초대가 만료되었습니다",
                    exception
            );
            case REVOKED -> new IdentityOperationException(
                    "MEMBER_INVITATION_REVOKED",
                    "구성원 초대가 폐기되었습니다",
                    exception
            );
            case USED -> new IdentityOperationException(
                    "MEMBER_INVITATION_USED",
                    "구성원 초대가 이미 사용되었습니다",
                    exception
            );
        };
    }

    private IdentityOperationException forbidden() {
        return new IdentityOperationException(
                "MEMBER_INVITATION_FORBIDDEN",
                "구성원 초대를 관리할 권한이 없습니다"
        );
    }

    private IdentityOperationException targetUnavailable() {
        return new IdentityOperationException(
                "MEMBER_INVITATION_TARGET_UNAVAILABLE",
                "구성원 초대 대상을 사용할 수 없습니다"
        );
    }

    private IdentityNotFoundException accountNotFound() {
        return new IdentityNotFoundException(
                "ACCOUNT_NOT_FOUND",
                "사용자 계정을 찾을 수 없습니다"
        );
    }

    private IdentityOperationException invitationNotFound() {
        return new IdentityOperationException(
                "MEMBER_INVITATION_NOT_FOUND",
                "구성원 초대를 찾을 수 없습니다"
        );
    }

    private IdentityOperationException invalidIdempotencyKey() {
        return new IdentityOperationException(
                "INVALID_IDEMPOTENCY_KEY",
                "Idempotency-Key는 canonical UUID 형식이어야 합니다"
        );
    }

    private IdentityOperationException invalidInput(String message) {
        return new IdentityOperationException("INVALID_INPUT", message);
    }

    private record LockedInvitationMembers(Member issuer, Member target) {
    }
}
