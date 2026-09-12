package com.personal.baton.adapter.out.external.identity;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.personal.baton.application.identity.port.out.HumanVerificationPort;
import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.http.client.HttpRedirects;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

public final class TurnstileHumanVerificationAdapter implements HumanVerificationPort {

    private static final URI BASE_URI = URI.create("https://challenges.cloudflare.com");
    private static final String SITEVERIFY_PATH = "/turnstile/v0/siteverify";
    private static final Set<String> CONFIGURATION_ERROR_CODES = Set.of(
            "missing-input-secret",
            "invalid-input-secret",
            "internal-error"
    );

    private final RestClient restClient;
    private final String siteKey;
    private final String secretKey;
    private final String expectedHostname;

    TurnstileHumanVerificationAdapter(
            RestClient restClient,
            String siteKey,
            String secretKey,
            String expectedHostname
    ) {
        this.restClient = Objects.requireNonNull(restClient, "Turnstile RestClient는 필수입니다");
        this.siteKey = requireValue(siteKey, "Turnstile site key");
        this.secretKey = requireValue(secretKey, "Turnstile secret key");
        this.expectedHostname = requireValue(expectedHostname, "Turnstile 허용 호스트");
    }

    @Override
    public Optional<String> siteKey() {
        return Optional.of(siteKey);
    }

    @Override
    public VerificationOutcome verify(HumanVerificationAttempt attempt) {
        Objects.requireNonNull(attempt, "자동 요청 방지 검증 값은 필수입니다");
        if (attempt.token() == null || attempt.token().isBlank()
                || attempt.token().length() > 2_048) {
            return VerificationOutcome.REJECTED;
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("secret", secretKey);
        form.add("response", attempt.token());
        if (attempt.remoteAddress() != null && !attempt.remoteAddress().isBlank()) {
            form.add("remoteip", attempt.remoteAddress());
        }

        try {
            return restClient.post()
                    .uri(SITEVERIFY_PATH)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(form)
                    .exchange((ignoredRequest, response) -> {
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            return VerificationOutcome.UNAVAILABLE;
                        }
                        SiteverifyResponse result = response.bodyTo(SiteverifyResponse.class);
                        return classify(result, attempt.expectedAction());
                    });
        } catch (RestClientException exception) {
            return VerificationOutcome.UNAVAILABLE;
        }
    }

    private VerificationOutcome classify(
            SiteverifyResponse response,
            String expectedAction
    ) {
        if (response == null) {
            return VerificationOutcome.UNAVAILABLE;
        }
        if (!response.success()) {
            boolean configurationFailure = response.errorCodes() != null
                    && response.errorCodes().stream().anyMatch(CONFIGURATION_ERROR_CODES::contains);
            return configurationFailure
                    ? VerificationOutcome.UNAVAILABLE
                    : VerificationOutcome.REJECTED;
        }
        if (!expectedHostname.equalsIgnoreCase(response.hostname())
                || !Objects.equals(expectedAction, response.action())) {
            return VerificationOutcome.REJECTED;
        }
        return VerificationOutcome.VERIFIED;
    }

    private static String requireValue(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + "가 필요합니다");
        }
        return value;
    }

    private record SiteverifyResponse(
            boolean success,
            String hostname,
            String action,
            @JsonProperty("error-codes") List<String> errorCodes
    ) {
    }

    @Component
    public static final class Factory {

        private final RestClient.Builder restClientBuilder;
        private final ClientHttpRequestFactoryBuilder<?> requestFactoryBuilder;
        private final HttpClientSettings managedHttpClientSettings;

        Factory(
                RestClient.Builder restClientBuilder,
                ClientHttpRequestFactoryBuilder<?> requestFactoryBuilder,
                HttpClientSettings managedHttpClientSettings
        ) {
            this.restClientBuilder = restClientBuilder;
            this.requestFactoryBuilder = requestFactoryBuilder;
            this.managedHttpClientSettings = managedHttpClientSettings;
        }

        public TurnstileHumanVerificationAdapter create(TurnstileProperties properties) {
            Objects.requireNonNull(properties, "Turnstile 설정은 필수입니다");
            properties.validateEnabled();
            HttpClientSettings settings = managedHttpClientSettings
                    .withTimeouts(properties.connectTimeout(), properties.readTimeout())
                    .withRedirects(HttpRedirects.DONT_FOLLOW);
            ClientHttpRequestFactory requestFactory = requestFactoryBuilder.build(settings);
            RestClient restClient = restClientBuilder.clone()
                    .baseUrl(BASE_URI)
                    .requestFactory(requestFactory)
                    .build();
            return new TurnstileHumanVerificationAdapter(
                    restClient,
                    properties.siteKey(),
                    properties.secretKey(),
                    properties.expectedHostname()
            );
        }
    }
}
