package com.personal.baton.application.identity;

import com.personal.baton.application.identity.error.IdentityConcurrentModificationException;
import com.personal.baton.application.identity.error.IdentityConflictException;
import com.personal.baton.application.identity.port.in.LoadLocalCredentialUseCase;
import com.personal.baton.application.identity.port.in.ResolveExternalLoginUseCase;
import com.personal.baton.application.identity.port.in.ValidateAccountSessionUseCase;
import com.personal.baton.application.identity.port.out.IdentityRepository;
import com.personal.baton.domain.identity.AccountIdentity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AccountAuthenticationService implements
        ResolveExternalLoginUseCase,
        LoadLocalCredentialUseCase,
        ValidateAccountSessionUseCase {

    private final IdentityRepository repository;
    private final ExternalLoginTransaction externalLoginTransaction;

    public AccountAuthenticationService(
            IdentityRepository repository,
            ExternalLoginTransaction externalLoginTransaction
    ) {
        this.repository = repository;
        this.externalLoginTransaction = externalLoginTransaction;
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ExternalLoginResult resolveExternalLogin(ExternalLoginCommand command) {
        try {
            return externalLoginTransaction.resolve(command);
        } catch (IdentityConflictException | IdentityConcurrentModificationException ignored) {
            // 실패한 REQUIRES_NEW 트랜잭션은 이 재시도 전에 끝난다.
            // 수렴 트랜잭션은 커밋된 공급자 주체의 승자 행을 잠가 여러 중복 콜백을
            // @Version 경쟁 대신 순서대로 처리한다.
            return externalLoginTransaction.resolveAfterContention(command);
        }
    }

    @Override
    public Optional<LocalCredentialResult> loadLocalCredential(String email) {
        return repository.findLocalLoginCredential(AccountIdentity.normalizeLocalEmail(email));
    }

    @Override
    public boolean isAccountSessionCurrent(UUID accountId, long sessionVersion) {
        return repository.findAccountSessionVersion(accountId)
                .filter(current -> current == sessionVersion)
                .isPresent();
    }
}
