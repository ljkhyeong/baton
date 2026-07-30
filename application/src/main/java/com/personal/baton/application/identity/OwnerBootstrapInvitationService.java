package com.personal.baton.application.identity;

import com.personal.baton.application.identity.error.IdentityNotFoundException;
import com.personal.baton.application.identity.error.IdentityOperationException;
import com.personal.baton.application.identity.error.MemberIdentityConflictException;
import com.personal.baton.application.identity.port.in.MemberIdentityUseCase.AuthenticatedAccount;
import com.personal.baton.application.identity.port.in.OwnerBootstrapInvitationUseCase;
import com.personal.baton.application.identity.port.out.IdentityRepository;
import com.personal.baton.application.identity.port.out.OwnerBootstrapInvitationRepository;
import com.personal.baton.application.identity.port.out.OwnerBootstrapInvitationRepository.InvitationInsertResult;
import com.personal.baton.domain.identity.MemberIdentityBinding;
import com.personal.baton.domain.identity.MemberIdentityRole;
import com.personal.baton.domain.identity.OwnerBootstrapInvitation;
import com.personal.baton.domain.identity.OwnerBootstrapInvitation.Acceptance;
import com.personal.baton.domain.identity.OwnerBootstrapInvitationStateException;
import com.personal.baton.domain.workspace.Member;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OwnerBootstrapInvitationService implements OwnerBootstrapInvitationUseCase {

    private static final String IDEMPOTENCY_HASH_DOMAIN =
            "baton:owner-bootstrap-invitation-idempotency:v1";
    private static final String TOKEN_DERIVATION_DOMAIN =
            "baton:owner-bootstrap-invitation-token:v1";
    private static final Duration MAXIMUM_TTL = Duration.ofHours(1);
    private static final int MINIMUM_SECRET_LENGTH = 32;

    private final IdentityRepository identityRepository;
    private final OwnerBootstrapInvitationRepository invitationRepository;
    private final Clock clock;
    private final String configuredBootstrapKey;
    private final String invitationHmacSecret;
    private final Duration invitationTtl;

    public OwnerBootstrapInvitationService(
            IdentityRepository identityRepository,
            OwnerBootstrapInvitationRepository invitationRepository,
            Clock clock,
            @Value("${baton.identity.bootstrap-key:}") String configuredBootstrapKey,
            @Value("${baton.identity.invitation-hmac-secret:}") String invitationHmacSecret,
            @Value("${baton.identity.bootstrap-invitation-ttl:PT1H}") String invitationTtl
    ) {
        this.identityRepository = identityRepository;
        this.invitationRepository = invitationRepository;
        this.clock = clock;
        this.configuredBootstrapKey = configuredBootstrapKey == null
                ? ""
                : configuredBootstrapKey;
        this.invitationHmacSecret = invitationHmacSecret == null
                ? ""
                : invitationHmacSecret;
        this.invitationTtl = parseTtl(invitationTtl);
    }

    @Override
    @Transactional
    public IssuedOwnerBootstrapInvitation issue(
            String operatorBootstrapKey,
            String idempotencyKey,
            IssueOwnerBootstrapInvitationCommand command
    ) {
        verifyIssuanceConfiguration();
        verifyOperator(operatorBootstrapKey);
        UUID canonicalIdempotencyKey = parseCanonicalIdempotencyKey(idempotencyKey);
        if (command == null) {
            throw invalidInput("bootstrap 초대 대상은 필수입니다");
        }

        String canonicalKey = canonicalIdempotencyKey.toString();
        String token = deriveToken(canonicalKey);
        String idempotencyKeyHash = IdentityCrypto.sha256Hex(
                IDEMPOTENCY_HASH_DOMAIN,
                canonicalKey
        );
        String tokenHash = IdentityCrypto.sha256HexOfToken(token);

        Optional<OwnerBootstrapInvitation> existing =
                invitationRepository.findByIdempotencyKeyHash(idempotencyKeyHash);
        if (existing.isPresent()) {
            return replayIssue(
                    existing.orElseThrow(),
                    command,
                    idempotencyKeyHash,
                    tokenHash,
                    token,
                    true
            );
        }

        Member member = identityRepository.findMemberByTeamIdAndIdForUpdate(
                        command.teamId(),
                        command.memberId()
                )
                .orElseThrow(() -> new IdentityNotFoundException(
                        "MEMBER_NOT_FOUND",
                        "구성원을 찾을 수 없습니다"
                ));
        if (!member.isActive()) {
            throw new IdentityOperationException(
                    "BOOTSTRAP_MEMBER_INACTIVE",
                    "활동 종료한 구성원에게 bootstrap 초대를 발급할 수 없습니다"
            );
        }
        if (identityRepository.findBindingByMemberId(command.memberId()).isPresent()
                || identityRepository.findOwnerBindingByTeamId(command.teamId()).isPresent()) {
            throw new IdentityOperationException(
                    "BOOTSTRAP_TARGET_UNAVAILABLE",
                    "bootstrap 초대 대상을 사용할 수 없습니다"
            );
        }

        Instant issuedAt = clock.instant();
        OwnerBootstrapInvitation candidate = OwnerBootstrapInvitation.issue(
                UUID.randomUUID(),
                command.teamId(),
                command.memberId(),
                idempotencyKeyHash,
                tokenHash,
                issuedAt,
                issuedAt.plus(invitationTtl)
        );
        InvitationInsertResult insertion = invitationRepository.insertIfAbsent(candidate);
        return replayIssue(
                insertion.invitation(),
                command,
                idempotencyKeyHash,
                tokenHash,
                token,
                !insertion.created()
        );
    }

    @Override
    @Transactional
    public AcceptedOwnerBootstrapInvitation accept(
            String token,
            AuthenticatedAccount authenticatedAccount
    ) {
        if (authenticatedAccount == null) {
            throw invalidInput("인증 사용자 계정은 필수입니다");
        }
        String tokenHash = acceptedTokenHash(token);
        UUID accountId = authenticatedAccount.accountId();
        identityRepository.findUserAccountByIdForUpdate(accountId)
                .orElseThrow(() -> new IdentityNotFoundException(
                        "ACCOUNT_NOT_FOUND",
                        "사용자 계정을 찾을 수 없습니다"
                ));

        OwnerBootstrapInvitation invitation =
                invitationRepository.findByTokenHashForUpdate(tokenHash)
                        .orElseThrow(this::invitationNotFound);
        Instant now = clock.instant();
        Acceptance acceptance = consume(invitation, accountId, now);
        if (acceptance == Acceptance.REPLAY) {
            return acceptedReplay(invitation, accountId);
        }

        Member member = identityRepository.findMemberByTeamIdAndIdForUpdate(
                        invitation.getTeamId(),
                        invitation.getMemberId()
                )
                .orElseThrow(() -> new IdentityNotFoundException(
                        "MEMBER_NOT_FOUND",
                        "구성원을 찾을 수 없습니다"
                ));
        if (!member.isActive()) {
            throw new IdentityOperationException(
                    "BOOTSTRAP_MEMBER_INACTIVE",
                    "활동 종료한 구성원은 bootstrap 초대를 수락할 수 없습니다"
            );
        }

        MemberIdentityBinding binding = ownerBinding(invitation, accountId, now);
        invitationRepository.save(invitation);
        return acceptedResult(invitation, binding);
    }

    @Override
    @Transactional
    public RevokedOwnerBootstrapInvitation revoke(
            String operatorBootstrapKey,
            UUID invitationId
    ) {
        verifyOperatorConfiguration();
        verifyOperator(operatorBootstrapKey);
        if (invitationId == null) {
            throw invalidInput("bootstrap 초대 식별자는 필수입니다");
        }
        OwnerBootstrapInvitation invitation = invitationRepository.findByIdForUpdate(invitationId)
                .orElseThrow(this::invitationNotFound);
        try {
            invitation.revoke(clock.instant());
        } catch (OwnerBootstrapInvitationStateException exception) {
            throw translateState(exception);
        }
        OwnerBootstrapInvitation saved = invitationRepository.save(invitation);
        return new RevokedOwnerBootstrapInvitation(saved.getId(), saved.getRevokedAt());
    }

    private MemberIdentityBinding ownerBinding(
            OwnerBootstrapInvitation invitation,
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

        Optional<MemberIdentityBinding> existingOwner =
                identityRepository.findOwnerBindingByTeamId(invitation.getTeamId());
        if (existingOwner.isPresent()
                && (!existingOwner.orElseThrow().getMemberId().equals(invitation.getMemberId())
                || !existingOwner.orElseThrow().belongsTo(accountId))) {
            throw new IdentityOperationException(
                    "BOOTSTRAP_OWNER_EXISTS",
                    "팀의 OWNER 신원 결속이 이미 존재합니다"
            );
        }

        MemberIdentityBinding binding = memberBinding
                .or(() -> accountBinding)
                .orElseGet(() -> MemberIdentityBinding.bind(
                        invitation.getMemberId(),
                        invitation.getTeamId(),
                        accountId,
                        now,
                        MemberIdentityRole.OWNER
                ));
        binding.grantOwner();
        try {
            return identityRepository.saveBinding(binding);
        } catch (MemberIdentityConflictException exception) {
            throw new IdentityOperationException(
                    "BOOTSTRAP_OWNER_EXISTS",
                    "팀의 OWNER 신원 결속이 이미 존재합니다",
                    exception
            );
        }
    }

    private AcceptedOwnerBootstrapInvitation acceptedReplay(
            OwnerBootstrapInvitation invitation,
            UUID accountId
    ) {
        MemberIdentityBinding binding = identityRepository.findBindingByMemberId(
                        invitation.getMemberId()
                )
                .filter(found -> found.belongsTo(accountId))
                .filter(MemberIdentityBinding::isOwner)
                .orElseThrow(() -> new IllegalStateException(
                        "소비된 bootstrap 초대의 OWNER 결속을 찾을 수 없습니다"
                ));
        return acceptedResult(invitation, binding);
    }

    private AcceptedOwnerBootstrapInvitation acceptedResult(
            OwnerBootstrapInvitation invitation,
            MemberIdentityBinding binding
    ) {
        return new AcceptedOwnerBootstrapInvitation(
                invitation.getId(),
                binding.getUserAccountId(),
                binding.getTeamId(),
                binding.getMemberId(),
                binding.getBoundAt(),
                binding.getRole()
        );
    }

    private Acceptance consume(
            OwnerBootstrapInvitation invitation,
            UUID accountId,
            Instant now
    ) {
        try {
            return invitation.consume(accountId, now);
        } catch (OwnerBootstrapInvitationStateException exception) {
            throw translateState(exception);
        }
    }

    private IssuedOwnerBootstrapInvitation replayIssue(
            OwnerBootstrapInvitation invitation,
            IssueOwnerBootstrapInvitationCommand command,
            String idempotencyKeyHash,
            String tokenHash,
            String token,
            boolean replayed
    ) {
        if (!invitation.matchesCreation(
                command.teamId(),
                command.memberId(),
                idempotencyKeyHash,
                tokenHash
        )) {
            throw new IdentityOperationException(
                    "BOOTSTRAP_IDEMPOTENCY_KEY_REUSED",
                    "동일한 멱등 키를 다른 bootstrap 초대 요청에 사용할 수 없습니다"
            );
        }
        return new IssuedOwnerBootstrapInvitation(
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
        return Base64.getUrlEncoder().withoutPadding().encodeToString(derived);
    }

    private String acceptedTokenHash(String token) {
        if (token == null || token.length() != 43) {
            throw invitationNotFound();
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(token);
            if (decoded.length != 32
                    || !Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(decoded)
                    .equals(token)) {
                throw invitationNotFound();
            }
        } catch (IllegalArgumentException exception) {
            throw invitationNotFound();
        }
        return IdentityCrypto.sha256HexOfToken(token);
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

    private void verifyIssuanceConfiguration() {
        verifyOperatorConfiguration();
        if (invitationHmacSecret.length() < MINIMUM_SECRET_LENGTH
                || IdentityCrypto.constantTimeEquals(
                configuredBootstrapKey,
                invitationHmacSecret
        )
                || invitationTtl == null
                || invitationTtl.isZero()
                || invitationTtl.isNegative()
                || invitationTtl.compareTo(MAXIMUM_TTL) > 0) {
            throw new IdentityOperationException(
                    "BOOTSTRAP_CONFIGURATION_INVALID",
                    "bootstrap 초대 발급 설정이 안전하지 않습니다"
            );
        }
    }

    private void verifyOperatorConfiguration() {
        if (configuredBootstrapKey.length() < MINIMUM_SECRET_LENGTH) {
            throw new IdentityOperationException(
                    "BOOTSTRAP_CONFIGURATION_INVALID",
                    "bootstrap 초대 발급 설정이 안전하지 않습니다"
            );
        }
    }

    private void verifyOperator(String operatorBootstrapKey) {
        if (!IdentityCrypto.constantTimeEquals(configuredBootstrapKey, operatorBootstrapKey)) {
            throw new IdentityOperationException(
                    "BOOTSTRAP_INVITATION_FORBIDDEN",
                    "bootstrap 초대를 발급할 권한이 없습니다"
            );
        }
    }

    private IdentityOperationException translateState(
            OwnerBootstrapInvitationStateException exception
    ) {
        return switch (exception.getReason()) {
            case EXPIRED -> new IdentityOperationException(
                    "BOOTSTRAP_INVITATION_EXPIRED",
                    "bootstrap 초대가 만료되었습니다",
                    exception
            );
            case REVOKED -> new IdentityOperationException(
                    "BOOTSTRAP_INVITATION_REVOKED",
                    "bootstrap 초대가 폐기되었습니다",
                    exception
            );
            case USED -> new IdentityOperationException(
                    "BOOTSTRAP_INVITATION_USED",
                    "bootstrap 초대가 이미 사용되었습니다",
                    exception
            );
        };
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

    private IdentityOperationException invitationNotFound() {
        return new IdentityOperationException(
                "BOOTSTRAP_INVITATION_NOT_FOUND",
                "bootstrap 초대를 찾을 수 없습니다"
        );
    }

    private static Duration parseTtl(String value) {
        try {
            return value == null ? null : Duration.parse(value);
        } catch (RuntimeException exception) {
            return null;
        }
    }
}
