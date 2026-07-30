package com.personal.baton.adapter.out.persistence.identity;

import com.personal.baton.application.identity.port.out.OidcIdentityRepository;
import com.personal.baton.domain.identity.OidcExternalIdentity;
import com.personal.baton.domain.identity.UserAccount;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class OidcIdentityPersistenceAdapter implements OidcIdentityRepository {

    private final UserAccountJpaRepository accountRepository;
    private final OidcExternalIdentityJpaRepository externalIdentityRepository;

    public OidcIdentityPersistenceAdapter(
            UserAccountJpaRepository accountRepository,
            OidcExternalIdentityJpaRepository externalIdentityRepository
    ) {
        this.accountRepository = accountRepository;
        this.externalIdentityRepository = externalIdentityRepository;
    }

    @Override
    @Transactional
    public ExternalIdentityResolution resolveOrCreate(
            UserAccount candidateAccount,
            OidcExternalIdentity candidateIdentity
    ) {
        OidcExternalIdentity existing = externalIdentityRepository.findByIdentityKeyHash(
                candidateIdentity.getIdentityKeyHash()
        ).orElse(null);
        if (existing != null) {
            return resolution(existing);
        }

        accountRepository.saveAndFlush(candidateAccount);
        externalIdentityRepository.insertIfAbsent(
                candidateIdentity.getId().toString(),
                candidateIdentity.getIdentityKeyHash(),
                candidateIdentity.getIssuer(),
                candidateIdentity.getSubject(),
                candidateIdentity.getUserAccountId().toString(),
                candidateIdentity.getLinkedAt()
        );

        OidcExternalIdentity stored = externalIdentityRepository.findByIdentityKeyHashForUpdate(
                        candidateIdentity.getIdentityKeyHash()
                )
                .orElseThrow(() -> new IllegalStateException(
                        "외부 신원 upsert 결과를 찾을 수 없습니다"
                ));
        if (!stored.getUserAccountId().equals(candidateAccount.getId())) {
            accountRepository.delete(candidateAccount);
            accountRepository.flush();
        }
        return resolution(stored);
    }

    private ExternalIdentityResolution resolution(OidcExternalIdentity identity) {
        UserAccount account = accountRepository.findByIdForUpdate(identity.getUserAccountId())
                .orElseThrow(() -> new IllegalStateException(
                        "외부 신원에 연결된 사용자 계정을 찾을 수 없습니다"
                ));
        return new ExternalIdentityResolution(account, identity);
    }
}
