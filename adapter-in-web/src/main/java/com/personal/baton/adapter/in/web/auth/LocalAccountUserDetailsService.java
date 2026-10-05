package com.personal.baton.adapter.in.web.auth;

import com.personal.baton.application.identity.port.in.LoadLocalCredentialUseCase;
import com.personal.baton.application.identity.port.in.LoadLocalCredentialUseCase.LocalCredentialResult;
import com.personal.baton.domain.identity.IdentityValidationException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

public final class LocalAccountUserDetailsService implements UserDetailsService {

    private static final String GENERIC_NOT_FOUND_MESSAGE =
            "이메일 또는 비밀번호가 올바르지 않습니다";

    private final ObjectProvider<LoadLocalCredentialUseCase> useCaseProvider;

    public LocalAccountUserDetailsService(ObjectProvider<LoadLocalCredentialUseCase> useCaseProvider) {
        this.useCaseProvider = useCaseProvider;
    }

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        LoadLocalCredentialUseCase useCase = useCaseProvider.getIfAvailable();
        if (useCase == null) {
            throw notFound();
        }
        try {
            LocalCredentialResult credential = useCase.loadLocalCredential(email)
                    .orElseThrow(this::notFound);
            if (!credential.emailVerified()) {
                throw notFound();
            }
            return new LocalAccountPrincipal(
                    credential.accountId(),
                    email,
                    credential.passwordHash(),
                    credential.sessionVersion()
            );
        } catch (IdentityValidationException exception) {
            throw notFound();
        }
    }

    private UsernameNotFoundException notFound() {
        return new UsernameNotFoundException(GENERIC_NOT_FOUND_MESSAGE);
    }
}
