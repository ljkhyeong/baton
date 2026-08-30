package com.personal.baton.adapter.in.web.auth;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

public final class AvailableClientAuthorizationRequestResolver implements
        OAuth2AuthorizationRequestResolver {

    private static final RequestMatcher AUTHORIZATION_REQUEST = PathPatternRequestMatcher.pathPattern(
            DefaultOAuth2AuthorizationRequestResolver.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI
                    + "/{registrationId}"
    );

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
        String registrationId = AUTHORIZATION_REQUEST.matcher(request)
                .getVariables().get("registrationId");
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
}
