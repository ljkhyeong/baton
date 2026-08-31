package com.personal.baton.adapter.in.web.auth;

import com.personal.baton.application.identity.port.in.ResolveExternalLoginUseCase;
import com.personal.baton.application.identity.AccountView;
import com.personal.baton.application.identity.port.in.ResolveExternalLoginUseCase.ExternalLoginCommand;
import com.personal.baton.domain.identity.IdentityProvider;
import java.util.Map;
import java.util.Objects;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;

public final class AccountOAuth2UserService {

    private static final String INVALID_PROVIDER_PROFILE_ERROR = "invalid_provider_profile";
    private static final String IDENTITY_INFRASTRUCTURE_UNAVAILABLE_ERROR =
            "identity_infrastructure_unavailable";
    private static final int MAXIMUM_DISPLAY_NAME_LENGTH = 100;

    private final ResolveExternalLoginUseCase resolveExternalLoginUseCase;
    private final OAuth2UserService<OidcUserRequest, OidcUser> oidcDelegate;
    private final OAuth2UserService<OAuth2UserRequest, OAuth2User> oauth2Delegate;

    public AccountOAuth2UserService(
            ResolveExternalLoginUseCase resolveExternalLoginUseCase,
            OAuth2UserService<OidcUserRequest, OidcUser> oidcDelegate,
            OAuth2UserService<OAuth2UserRequest, OAuth2User> oauth2Delegate
    ) {
        this.resolveExternalLoginUseCase = Objects.requireNonNull(resolveExternalLoginUseCase);
        this.oidcDelegate = Objects.requireNonNull(oidcDelegate);
        this.oauth2Delegate = Objects.requireNonNull(oauth2Delegate);
    }

    public OidcUser loadOidcUser(OidcUserRequest request) {
        if (!"google".equals(request.getClientRegistration().getRegistrationId())) {
            throw invalidProviderProfile("지원하지 않는 OIDC 공급자입니다");
        }
        OidcUser providerUser = oidcDelegate.loadUser(request);
        String subject = requiredText(providerUser.getSubject(), "Google subject가 없습니다");
        String email = optionalText(providerUser.getEmail());
        String displayName = displayName(
                providerUser.getFullName(),
                providerUser.getGivenName(),
                providerUser.getPreferredUsername(),
                email,
                "Google 사용자"
        );
        AccountView account = resolveIdentity(
                IdentityProvider.GOOGLE,
                subject,
                email,
                Boolean.TRUE.equals(providerUser.getEmailVerified()),
                displayName
        );
        return new OidcAccountPrincipal(account.accountId(), account.sessionVersion(), providerUser);
    }

    public OAuth2User loadOAuth2User(OAuth2UserRequest request) {
        if (!"naver".equals(request.getClientRegistration().getRegistrationId())) {
            throw invalidProviderProfile("지원하지 않는 OAuth2 공급자입니다");
        }
        OAuth2User providerUser = oauth2Delegate.loadUser(request);
        Map<?, ?> profile = naverProfile(providerUser.getAttributes());
        String subject = requiredText(profile.get("id"), "Naver profile id가 없습니다");
        String email = optionalText(profile.get("email"));
        String displayName = displayName(
                optionalText(profile.get("name")),
                optionalText(profile.get("nickname")),
                email,
                "Naver 사용자"
        );
        AccountView account = resolveIdentity(
                IdentityProvider.NAVER,
                subject,
                email,
                false,
                displayName
        );
        return new OAuthAccountPrincipal(account.accountId(), account.sessionVersion(), providerUser);
    }

    private AccountView resolveIdentity(
            IdentityProvider provider,
            String providerSubject,
            String email,
            boolean emailVerified,
            String displayName
    ) {
        try {
            return resolveExternalLoginUseCase.resolveExternalLogin(new ExternalLoginCommand(
                    provider,
                    providerSubject,
                    email,
                    emailVerified,
                    displayName
            )).account();
        } catch (RuntimeException exception) {
            if (IdentityInfrastructureFailures.find(exception).isEmpty()) {
                throw exception;
            }
            throw new OAuth2AuthenticationException(
                    new OAuth2Error(IDENTITY_INFRASTRUCTURE_UNAVAILABLE_ERROR),
                    "외부 계정 identity를 일시적으로 처리할 수 없습니다",
                    exception
            );
        }
    }

    private Map<?, ?> naverProfile(Map<String, Object> attributes) {
        Object response = attributes.get("response");
        if (response instanceof Map<?, ?> profile) {
            return profile;
        }
        throw invalidProviderProfile("Naver profile response가 없습니다");
    }

    private String displayName(String... candidates) {
        for (String candidate : candidates) {
            String value = optionalText(candidate);
            if (value != null) {
                return limitDisplayName(value, MAXIMUM_DISPLAY_NAME_LENGTH);
            }
        }
        throw invalidProviderProfile("표시 이름을 결정할 수 없습니다");
    }

    private String requiredText(Object value, String message) {
        String text = optionalText(value);
        if (text == null) {
            throw invalidProviderProfile(message);
        }
        return text;
    }

    private String optionalText(Object value) {
        if (!(value instanceof String text) || text.isBlank()) {
            return null;
        }
        return text.trim();
    }

    private String limitDisplayName(String value, int maximumLength) {
        if (value.length() <= maximumLength) {
            return value;
        }
        int endIndex = maximumLength;
        if (Character.isHighSurrogate(value.charAt(endIndex - 1))
                && Character.isLowSurrogate(value.charAt(endIndex))) {
            endIndex--;
        }
        return value.substring(0, endIndex);
    }

    private OAuth2AuthenticationException invalidProviderProfile(String message) {
        return new OAuth2AuthenticationException(
                new OAuth2Error(INVALID_PROVIDER_PROFILE_ERROR),
                message
        );
    }
}
