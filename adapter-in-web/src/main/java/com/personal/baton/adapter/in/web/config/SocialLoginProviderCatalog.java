package com.personal.baton.adapter.in.web.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

public final class SocialLoginProviderCatalog {

    private final ClientRegistrationRepository registrations;
    private final List<String> availableProviderIds;

    public SocialLoginProviderCatalog(
            SocialLoginProperties properties,
            ClientRegistrationRepository registrations
    ) {
        this.registrations = Objects.requireNonNull(registrations);
        rejectUnsupportedRegistrations(properties, registrations);
        this.availableProviderIds = properties.supportedProviderIds().stream()
                .filter(providerId -> registrations.findByRegistrationId(providerId) != null)
                .toList();
        if (availableProviderIds.isEmpty()) {
            throw new IllegalStateException(
                    "OAuth2 로그인이 활성화됐지만 지원하는 Google/Naver registration이 없습니다"
            );
        }
    }

    public ClientRegistrationRepository registrations() {
        return registrations;
    }

    public List<String> availableProviderIds() {
        return availableProviderIds;
    }

    public boolean isAvailable(String providerId) {
        return availableProviderIds.contains(providerId);
    }

    private void rejectUnsupportedRegistrations(
            SocialLoginProperties properties,
            ClientRegistrationRepository registrations
    ) {
        if (!(registrations instanceof Iterable<?> iterable)) {
            throw new IllegalStateException(
                    "OAuth2 registration repository는 구성된 provider를 열거할 수 있어야 합니다"
            );
        }

        List<String> unsupportedProviderIds = new ArrayList<>();
        for (Object registration : iterable) {
            if (!(registration instanceof ClientRegistration clientRegistration)) {
                throw new IllegalStateException(
                        "OAuth2 registration repository에 해석할 수 없는 항목이 있습니다"
                );
            }
            String registrationId = clientRegistration.getRegistrationId();
            if (!properties.supports(registrationId)) {
                unsupportedProviderIds.add(registrationId);
            }
        }
        if (!unsupportedProviderIds.isEmpty()) {
            throw new IllegalStateException(
                    "지원하지 않는 OAuth2 registration입니다: "
                            + String.join(", ", unsupportedProviderIds)
            );
        }
    }
}
