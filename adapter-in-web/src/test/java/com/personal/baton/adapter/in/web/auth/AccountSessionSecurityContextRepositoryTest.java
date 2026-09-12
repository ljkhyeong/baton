package com.personal.baton.adapter.in.web.auth;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.context.SecurityContextRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;

class AccountSessionSecurityContextRepositoryTest {

    private static final UUID ACCOUNT_ID =
            UUID.fromString("8e448211-66ae-44ab-9888-c4960648c22b");

    @DisplayName("BATON 계정이 아닌 OAuth 인증 결과는 저장하지 않고 차단한다")
    @Test
    void rejectsRawProviderAuthenticationBeforeDelegatingSave() {
        OidcUser rawProviderUser = mock(OidcUser.class);
        OAuth2AuthenticationToken providerAuthentication = new OAuth2AuthenticationToken(
                rawProviderUser,
                Set.of(new SimpleGrantedAuthority("OIDC_USER")),
                "google"
        );
        SecurityContext providerContext = SecurityContextHolder.createEmptyContext();
        providerContext.setAuthentication(providerAuthentication);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        SecurityContextRepository delegate = mock(SecurityContextRepository.class);
        doAnswer(invocation -> {
            assertThat(providerContext.getAuthentication()).isNull();
            return null;
        }).when(delegate).saveContext(same(providerContext), same(request), same(response));
        AccountSessionSecurityContextRepository repository =
                new AccountSessionSecurityContextRepository(delegate);

        assertThatThrownBy(() -> repository.saveContext(
                providerContext,
                request,
                response
        )).isInstanceOf(IllegalStateException.class);

        verify(delegate).saveContext(same(providerContext), same(request), same(response));
        assertThat(providerContext.getAuthentication()).isNull();
    }
}
