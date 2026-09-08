package com.personal.baton.adapter.out.external.watch;

import com.personal.baton.application.watch.WatchMonitorDelivery;
import com.personal.baton.application.watch.port.out.WatchMonitorClient;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.http.client.HttpRedirects;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

public final class RestClientWatchMonitorClient implements WatchMonitorClient {

    private static final String MONITOR_PATH = "/api/v1/resource-monitors/{resourceReference}";

    private final RestClient restClient;

    RestClientWatchMonitorClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public SynchronizationResult synchronize(WatchMonitorDelivery delivery) {
        WatchSynchronizationRequest request = new WatchSynchronizationRequest(
                delivery.sourceRevision(),
                delivery.monitoringState().name(),
                delivery.targetUrl()
        );

        try {
            return restClient.put()
                    .uri(MONITOR_PATH, delivery.resourceReference())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON)
                    .body(request)
                    .exchange((ignoredRequest, response) -> classify(
                            response.getStatusCode(),
                            readKnownProblemCode(response)
                    ));
        } catch (ResourceAccessException exception) {
            return SynchronizationResult.retryable("WATCH_NETWORK_FAILURE");
        } catch (RestClientException exception) {
            return SynchronizationResult.retryable("WATCH_CLIENT_FAILURE");
        }
    }

    private SynchronizationResult classify(HttpStatusCode status, String problemCode) {
        if (status.isSameCodeAs(HttpStatus.OK)) {
            return SynchronizationResult.delivered();
        }
        if (status.isSameCodeAs(HttpStatus.CONFLICT)) {
            if ("STALE_SOURCE_REVISION".equals(problemCode)) {
                return SynchronizationResult.stale();
            }
            if ("SOURCE_REVISION_CONFLICT".equals(problemCode)) {
                return SynchronizationResult.permanentFailure(problemCode);
            }
            return SynchronizationResult.permanentFailure(httpCode(status));
        }
        if (status.isSameCodeAs(HttpStatus.UNPROCESSABLE_CONTENT)) {
            if ("INVALID_TARGET_URL".equals(problemCode)) {
                return SynchronizationResult.invalidTarget();
            }
            return SynchronizationResult.permanentFailure(httpCode(status));
        }
        if (status.isSameCodeAs(HttpStatus.TOO_MANY_REQUESTS) || status.is5xxServerError()) {
            return SynchronizationResult.retryable(httpCode(status));
        }
        return SynchronizationResult.permanentFailure(httpCode(status));
    }

    private String readKnownProblemCode(
            RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response
    ) throws IOException {
        if (!response.getStatusCode().isSameCodeAs(HttpStatus.CONFLICT)
                && !response.getStatusCode().isSameCodeAs(HttpStatus.UNPROCESSABLE_CONTENT)) {
            return null;
        }
        try {
            WatchProblemResponse problem = response.bodyTo(WatchProblemResponse.class);
            return problem == null ? null : problem.code();
        } catch (RestClientException exception) {
            return null;
        }
    }

    private String httpCode(HttpStatusCode status) {
        return "HTTP_" + status.value();
    }

    private record WatchSynchronizationRequest(
            long sourceRevision,
            String monitoringState,
            String targetUrl
    ) {
    }

    private record WatchProblemResponse(String code) {
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

        public RestClientWatchMonitorClient create(
                URI baseUri,
                String bearerToken,
                Duration connectTimeout,
                Duration readTimeout
        ) {
            return new RestClientWatchMonitorClient(buildRestClient(baseUri, bearerToken, connectTimeout, readTimeout));
        }

        public RestClientWatchInspectionClient createInspection(URI baseUri, String bearerToken) {
            return new RestClientWatchInspectionClient(buildRestClient(baseUri, bearerToken,
                    Duration.ofSeconds(1), Duration.ofSeconds(2)));
        }

        private RestClient buildRestClient(URI baseUri, String bearerToken,
                                           Duration connectTimeout, Duration readTimeout) {
            HttpClientSettings settings = managedHttpClientSettings
                    .withTimeouts(
                            connectTimeout,
                            readTimeout
                    )
                    .withRedirects(HttpRedirects.DONT_FOLLOW);
            ClientHttpRequestFactory requestFactory = requestFactoryBuilder.build(settings);
            return restClientBuilder.clone()
                    .baseUrl(baseUri)
                    .requestFactory(requestFactory)
                    .defaultHeaders(headers -> headers.setBearerAuth(bearerToken))
                    .build();
        }
    }
}
