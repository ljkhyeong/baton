package com.personal.baton.application.identity;

import com.personal.baton.application.identity.error.AccountNotFoundException;
import com.personal.baton.application.identity.port.in.ResolveExternalLoginUseCase.ExternalLoginCommand;
import com.personal.baton.application.identity.port.in.ResolveExternalLoginUseCase.ExternalLoginResult;
import com.personal.baton.application.identity.port.out.IdentityRepository;
import com.personal.baton.domain.identity.Account;
import com.personal.baton.domain.identity.AccountIdentity;
import com.personal.baton.domain.identity.IdentityProvider;
import com.personal.baton.domain.identity.IdentityValidationException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExternalLoginTransaction {

    private final IdentityRepository repository;
    private final Clock clock;

    public ExternalLoginTransaction(IdentityRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ExternalLoginResult resolve(ExternalLoginCommand command) {
        return resolve(command, false);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ExternalLoginResult resolveAfterContention(ExternalLoginCommand command) {
        return resolve(command, true);
    }

    private ExternalLoginResult resolve(
            ExternalLoginCommand command,
            boolean lockExistingIdentity
    ) {
        if (command == null) {
            throw new IdentityValidationException("외부 로그인 요청은 필수입니다");
        }
        requireExternal(command.provider());
        String providerSubject = AccountIdentity.normalizeExternalSubject(
                command.providerSubject()
        );
        Instant now = clock.instant();
        Optional<AccountIdentity> discovered = lockExistingIdentity
                ? repository.findIdentityForUpdate(command.provider(), providerSubject)
                : repository.findIdentity(command.provider(), providerSubject);
        AccountIdentity existing = discovered.orElse(null);
        if (existing != null) {
            existing.recordExternalAuthentication(
                    command.email(),
                    command.emailVerified(),
                    now
            );
            repository.saveIdentity(existing);
            Account account = repository.findAccountById(existing.getAccountId())
                    .orElseThrow(AccountNotFoundException::new);
            return new ExternalLoginResult(AccountView.from(
                    account,
                    repository.findIdentitiesByAccountId(account.getId())
            ), false);
        }

        Account account = Account.create(UUID.randomUUID(), command.displayName(), now);
        AccountIdentity identity = AccountIdentity.createExternal(
                UUID.randomUUID(),
                account.getId(),
                command.provider(),
                providerSubject,
                command.email(),
                command.emailVerified(),
                now
        );
        repository.saveAccount(account);
        repository.saveIdentity(identity);
        return new ExternalLoginResult(AccountView.from(account, List.of(identity)), true);
    }

    private void requireExternal(IdentityProvider provider) {
        if (provider == null || !provider.isExternal()) {
            throw new IdentityValidationException(
                    "Google 또는 Naver 신원만 외부 신원으로 사용할 수 있습니다"
            );
        }
    }
}
