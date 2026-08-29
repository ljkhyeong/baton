package com.personal.baton.adapter.out.external.brief;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.http.client.HttpRedirects;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class BriefRestClientFactory {

    private final RestClient.Builder restClientBuilder;
    private final ClientHttpRequestFactoryBuilder<?> requestFactoryBuilder;
    private final HttpClientSettings managedHttpClientSettings;

    public BriefRestClientFactory(
            RestClient.Builder restClientBuilder,
            ClientHttpRequestFactoryBuilder<?> requestFactoryBuilder,
            HttpClientSettings managedHttpClientSettings
    ) {
        this.restClientBuilder = restClientBuilder;
        this.requestFactoryBuilder = requestFactoryBuilder;
        this.managedHttpClientSettings = managedHttpClientSettings;
    }

    public RestClientBriefContinuityClient createContinuityClient(
            URI baseUri,
            String bearerToken,
            Duration connectTimeout,
            Duration readTimeout
    ) {
        return new RestClientBriefContinuityClient(createRestClient(
                baseUri,
                bearerToken,
                connectTimeout,
                readTimeout
        ));
    }

    public RestClientBriefEditionServiceClient createEditionServiceClient(
            URI baseUri,
            String bearerToken,
            Duration connectTimeout,
            Duration readTimeout
    ) {
        return new RestClientBriefEditionServiceClient(createRestClient(
                baseUri,
                bearerToken,
                connectTimeout,
                readTimeout
        ));
    }

    private RestClient createRestClient(
            URI baseUri,
            String bearerToken,
            Duration connectTimeout,
            Duration readTimeout
    ) {
        HttpClientSettings settings = managedHttpClientSettings
                .withTimeouts(connectTimeout, readTimeout)
                .withRedirects(HttpRedirects.DONT_FOLLOW);
        ClientHttpRequestFactory requestFactory = requestFactoryBuilder.build(settings);
        RestClient.Builder builder = restClientBuilder.clone()
                .baseUrl(Objects.requireNonNull(baseUri, "BRIEF base URI는 필수입니다"))
                .requestFactory(requestFactory);
        if (bearerToken != null) {
            builder.defaultHeaders(headers -> headers.setBearerAuth(bearerToken));
        }
        return builder.build();
    }
}
