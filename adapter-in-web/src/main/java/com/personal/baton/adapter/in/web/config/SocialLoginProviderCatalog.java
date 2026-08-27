package com.personal.baton.adapter.in.web.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

public final class SocialLoginProviderCatalog {

    private static final List<String> SUPPORTED_PROVIDER_IDS = List.of(
            "google",
            "naver"
    );

    private final ClientRegistrationRepository registrations;
    private final List<String> availableProviderIds;

    public SocialLoginProviderCatalog(ClientRegistrationRepository registrations) {
        this.registrations = Objects.requireNonNull(registrations);
        rejectUnsupportedRegistrations(registrations);
        this.availableProviderIds = SUPPORTED_PROVIDER_IDS.stream()
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

    private void rejectUnsupportedRegistrations(ClientRegistrationRepository registrations) {
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
            if (!SUPPORTED_PROVIDER_IDS.contains(registrationId)) {
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
