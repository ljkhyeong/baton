package com.personal.baton.adapter.in.web.auth;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

public final class AvailableClientAuthorizationRequestResolver implements
        OAuth2AuthorizationRequestResolver {

    private final ClientRegistrationRepository registrations;
    private final DefaultOAuth2AuthorizationRequestResolver delegate;

    public AvailableClientAuthorizationRequestResolver(
            ClientRegistrationRepository registrations
    ) {
        this.registrations = Objects.requireNonNull(registrations);
        this.delegate = new DefaultOAuth2AuthorizationRequestResolver(registrations);
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
        String registrationId = registrationId(request);
        if (!isAvailable(registrationId)) {
            return null;
        }
        return delegate.resolve(request);
    }

    @Override
    public OAuth2AuthorizationRequest resolve(
            HttpServletRequest request,
            String clientRegistrationId
    ) {
        if (!isAvailable(clientRegistrationId)) {
            return null;
        }
        return delegate.resolve(request, clientRegistrationId);
    }

    private boolean isAvailable(String registrationId) {
        return registrationId != null
                && registrations.findByRegistrationId(registrationId) != null;
    }

    private String registrationId(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        String prefix = DefaultOAuth2AuthorizationRequestResolver
                .DEFAULT_AUTHORIZATION_REQUEST_BASE_URI + "/";
        if (!path.startsWith(prefix)) {
            return null;
        }
        String candidate = path.substring(prefix.length());
        if (candidate.isBlank()
                || candidate.indexOf('/') >= 0
                || candidate.indexOf(';') >= 0) {
            return null;
        }
        return candidate;
    }
}
