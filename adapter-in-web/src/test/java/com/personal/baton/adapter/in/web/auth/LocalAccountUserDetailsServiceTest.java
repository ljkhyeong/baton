package com.personal.baton.adapter.in.web.auth;

import com.personal.baton.application.identity.port.in.LoadLocalCredentialUseCase;
import com.personal.baton.application.identity.port.in.UpdateLocalCredentialPasswordUseCase;
import com.personal.baton.application.identity.port.in.UpdateLocalCredentialPasswordUseCase.UpdateLocalCredentialPasswordCommand;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LocalAccountUserDetailsServiceTest {

    @DisplayName("Spring Security password upgrade는 application port에 새 encoded hash만 전달한다")
    @Test
    void delegatesPasswordUpgradeToApplicationPort() {
        @SuppressWarnings("unchecked")
        ObjectProvider<LoadLocalCredentialUseCase> loadProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<UpdateLocalCredentialPasswordUseCase> updateProvider =
                mock(ObjectProvider.class);
        UpdateLocalCredentialPasswordUseCase updateUseCase = mock(
                UpdateLocalCredentialPasswordUseCase.class
        );
        when(updateProvider.getIfAvailable()).thenReturn(updateUseCase);
        LocalAccountUserDetailsService service = new LocalAccountUserDetailsService(
                loadProvider,
                updateProvider
        );
        UUID accountId = UUID.randomUUID();
        LocalAccountPrincipal principal = new LocalAccountPrincipal(
                accountId,
                "member@example.com",
                "{bcrypt}legacy-hash"
        );
        String upgradedHash = "{pbkdf2@SpringSecurity_v5_8}upgraded-hash";

        LocalAccountPrincipal upgraded = (LocalAccountPrincipal) service.updatePassword(
                principal,
                upgradedHash
        );

        ArgumentCaptor<UpdateLocalCredentialPasswordCommand> command =
                ArgumentCaptor.forClass(UpdateLocalCredentialPasswordCommand.class);
        verify(updateUseCase).updateLocalCredentialPassword(command.capture());
        assertThat(command.getValue().accountId()).isEqualTo(accountId);
        assertThat(command.getValue().encodedPassword()).isEqualTo(upgradedHash);
        assertThat(command.getValue().toString()).doesNotContain(upgradedHash);
        assertThat(upgraded.accountId()).isEqualTo(accountId);
        assertThat(upgraded.getUsername()).isEqualTo("member@example.com");
        assertThat(upgraded.getPassword()).isEqualTo(upgradedHash);
    }
}
