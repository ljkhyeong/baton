package com.personal.baton.adapter.out.external.brief;

import com.personal.baton.application.brief.BriefContinuityDelivery;
import com.personal.baton.application.brief.port.out.BriefContinuityClient;
import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.http.client.HttpRedirects;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

public final class RestClientBriefContinuityClient implements BriefContinuityClient {

    private static final String EVENT_PATH = "/api/v1/events";

    private final RestClient restClient;

    RestClientBriefContinuityClient(RestClient restClient) {
        this.restClient = Objects.requireNonNull(restClient, "BRIEF RestClient는 필수입니다");
    }

    @Override
    public DeliveryResult deliver(BriefContinuityDelivery delivery) {
        Objects.requireNonNull(delivery, "BRIEF delivery는 필수입니다");
        try {
            return restClient.post()
                    .uri(EVENT_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON)
                    .body(delivery.event())
                    .exchange((ignoredRequest, response) -> classify(response.getStatusCode()));
        } catch (ResourceAccessException exception) {
            return DeliveryResult.retryable("BRIEF_NETWORK_FAILURE");
        } catch (RestClientException exception) {
            return DeliveryResult.retryable("BRIEF_CLIENT_FAILURE");
        }
    }

    private DeliveryResult classify(HttpStatusCode status) {
        if (status.value() == 200 || status.value() == 202) {
            return DeliveryResult.delivered(httpCode(status));
        }
        if (status.value() == 429 || status.is5xxServerError()) {
            return DeliveryResult.retryable(httpCode(status));
        }
        return DeliveryResult.permanentFailure(httpCode(status));
    }

    private String httpCode(HttpStatusCode status) {
        return "HTTP_" + status.value();
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
            this.restClientBuilder = Objects.requireNonNull(
                    restClientBuilder,
                    "BRIEF RestClient builder는 필수입니다"
            );
            this.requestFactoryBuilder = Objects.requireNonNull(
                    requestFactoryBuilder,
                    "BRIEF HTTP request factory builder는 필수입니다"
            );
            this.managedHttpClientSettings = Objects.requireNonNull(
                    managedHttpClientSettings,
                    "BRIEF HTTP client settings는 필수입니다"
            );
        }

        public RestClientBriefContinuityClient create(
                URI baseUri,
                Duration connectTimeout,
                Duration readTimeout
        ) {
            Objects.requireNonNull(baseUri, "BRIEF base URI는 필수입니다");
            HttpClientSettings settings = managedHttpClientSettings
                    .withTimeouts(
                            Objects.requireNonNull(connectTimeout, "BRIEF connect timeout은 필수입니다"),
                            Objects.requireNonNull(readTimeout, "BRIEF read timeout은 필수입니다")
                    )
                    .withRedirects(HttpRedirects.DONT_FOLLOW);
            ClientHttpRequestFactory requestFactory = requestFactoryBuilder.build(settings);
            RestClient restClient = restClientBuilder.clone()
                    .baseUrl(baseUri)
                    .requestFactory(requestFactory)
                    .build();
            return new RestClientBriefContinuityClient(restClient);
        }
    }
}
