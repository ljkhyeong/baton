package com.personal.baton.adapter.out.external.http;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.http.client.HttpRedirects;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

// Spring Boot가 관리하는 RestClient·요청 팩터리 설정을 유지하고 연동별 시간 제한과 리디렉션 차단만 바꾼다.
@Component
public class OutboundRestClients {

    private final RestClient.Builder restClientBuilder;
    private final ClientHttpRequestFactoryBuilder<?> requestFactoryBuilder;
    private final HttpClientSettings managedHttpClientSettings;

    public OutboundRestClients(
            RestClient.Builder restClientBuilder,
            ClientHttpRequestFactoryBuilder<?> requestFactoryBuilder,
            HttpClientSettings managedHttpClientSettings
    ) {
        this.restClientBuilder = restClientBuilder;
        this.requestFactoryBuilder = requestFactoryBuilder;
        this.managedHttpClientSettings = managedHttpClientSettings;
    }

    public RestClient.Builder builder(URI baseUri, Duration connectTimeout, Duration readTimeout) {
        HttpClientSettings settings = managedHttpClientSettings
                .withTimeouts(connectTimeout, readTimeout)
                .withRedirects(HttpRedirects.DONT_FOLLOW);
        return restClientBuilder.clone()
                .baseUrl(baseUri)
                .requestFactory(requestFactoryBuilder.build(settings));
    }

    public RestClient withBearer(URI baseUri, String bearerToken, Duration connectTimeout, Duration readTimeout) {
        return builder(baseUri, connectTimeout, readTimeout)
                .defaultHeaders(headers -> headers.setBearerAuth(bearerToken))
                .build();
    }
}
