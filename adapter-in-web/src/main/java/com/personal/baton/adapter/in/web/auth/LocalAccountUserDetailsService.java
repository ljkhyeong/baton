package com.personal.baton.adapter.in.web.auth;

import com.personal.baton.application.identity.port.in.LoadLocalCredentialUseCase;
import com.personal.baton.application.identity.port.in.LoadLocalCredentialUseCase.LocalCredentialResult;
import com.personal.baton.application.identity.port.in.UpdateLocalCredentialPasswordUseCase;
import com.personal.baton.application.identity.port.in.UpdateLocalCredentialPasswordUseCase.UpdateLocalCredentialPasswordCommand;
import com.personal.baton.domain.identity.IdentityValidationException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UserDetailsPasswordService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

public final class LocalAccountUserDetailsService implements
        UserDetailsService,
        UserDetailsPasswordService {

    private static final String GENERIC_NOT_FOUND_MESSAGE =
            "이메일 또는 비밀번호가 올바르지 않습니다";

    private final ObjectProvider<LoadLocalCredentialUseCase> useCaseProvider;
    private final ObjectProvider<UpdateLocalCredentialPasswordUseCase> updateUseCaseProvider;

    public LocalAccountUserDetailsService(
            ObjectProvider<LoadLocalCredentialUseCase> useCaseProvider,
            ObjectProvider<UpdateLocalCredentialPasswordUseCase> updateUseCaseProvider
    ) {
        this.useCaseProvider = useCaseProvider;
        this.updateUseCaseProvider = updateUseCaseProvider;
    }

    @Override
    public UserDetails updatePassword(UserDetails user, String newPassword) {
        if (!(user instanceof LocalAccountPrincipal principal)) {
            throw new IllegalArgumentException("로컬 계정 principal만 비밀번호를 갱신할 수 있습니다");
        }
        UpdateLocalCredentialPasswordUseCase useCase = updateUseCaseProvider.getIfAvailable();
        if (useCase == null) {
            throw new IllegalStateException("로컬 자격 증명 갱신 port가 구성되지 않았습니다");
        }
        useCase.updateLocalCredentialPassword(new UpdateLocalCredentialPasswordCommand(
                principal.accountId(),
                newPassword
        ));
        return new LocalAccountPrincipal(
                principal.accountId(),
                principal.getUsername(),
                newPassword
        );
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
                    credential.passwordHash()
            );
        } catch (IdentityValidationException exception) {
            throw notFound();
        }
    }

    private UsernameNotFoundException notFound() {
        return new UsernameNotFoundException(GENERIC_NOT_FOUND_MESSAGE);
    }
}
