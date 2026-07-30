package com.personal.baton.application.identity;

import com.personal.baton.application.identity.error.IdentityOperationException;
import com.personal.baton.application.identity.port.in.OidcIdentityUseCase;
import com.personal.baton.application.identity.port.out.OidcIdentityRepository;
import com.personal.baton.application.identity.port.out.OidcIdentityRepository.ExternalIdentityResolution;
import com.personal.baton.domain.identity.OidcExternalIdentity;
import com.personal.baton.domain.identity.UserAccount;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OidcIdentityService implements OidcIdentityUseCase {

    private static final String IDENTITY_KEY_DOMAIN = "baton:oidc-external-identity:v1";

    private final OidcIdentityRepository repository;
    private final Clock clock;

    public OidcIdentityService(OidcIdentityRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    @Transactional
    public OidcAccountResult resolveAccount(VerifiedOidcIdentity verifiedIdentity) {
        ValidatedIdentity identity = validate(verifiedIdentity);
        Instant now = clock.instant();
        UUID candidateAccountId = UUID.randomUUID();
        UserAccount candidateAccount = UserAccount.create(candidateAccountId, now);
        OidcExternalIdentity candidateIdentity = OidcExternalIdentity.link(
                UUID.randomUUID(),
                IdentityCrypto.sha256Hex(
                        IDENTITY_KEY_DOMAIN,
                        List.of(identity.issuer(), identity.subject())
                ),
                identity.issuer(),
                identity.subject(),
                candidateAccountId,
                now
        );

        ExternalIdentityResolution resolution = repository.resolveOrCreate(
                candidateAccount,
                candidateIdentity
        );
        if (!resolution.externalIdentity().represents(identity.issuer(), identity.subject())) {
            throw new IdentityOperationException(
                    "EXTERNAL_IDENTITY_CONFLICT",
                    "외부 신원 식별자가 기존 신원과 충돌합니다"
            );
        }
        if (!resolution.account().getId().equals(
                resolution.externalIdentity().getUserAccountId()
        )) {
            throw new IllegalStateException("외부 신원과 내부 사용자 계정 연결이 일치하지 않습니다");
        }
        return new OidcAccountResult(
                resolution.account().getId(),
                resolution.account().getCreatedAt()
        );
    }

    private ValidatedIdentity validate(VerifiedOidcIdentity identity) {
        if (identity == null) {
            throw invalidIdentity();
        }
        String issuer = identity.issuer();
        String subject = identity.subject();
        if (issuer.isBlank()
                || issuer.length() > 512
                || subject.isBlank()
                || subject.length() > 255) {
            throw invalidIdentity();
        }
        try {
            URI parsed = new URI(issuer);
            if (!parsed.isAbsolute()
                    || !"https".equalsIgnoreCase(parsed.getScheme())
                    || parsed.getHost() == null
                    || parsed.getUserInfo() != null
                    || parsed.getQuery() != null
                    || parsed.getFragment() != null) {
                throw invalidIdentity();
            }
        } catch (URISyntaxException exception) {
            throw invalidIdentity();
        }
        return new ValidatedIdentity(issuer, subject);
    }

    private IdentityOperationException invalidIdentity() {
        return new IdentityOperationException(
                "INVALID_EXTERNAL_IDENTITY",
                "검증된 OIDC issuer와 subject가 올바르지 않습니다"
        );
    }

    private record ValidatedIdentity(String issuer, String subject) {
    }
}
