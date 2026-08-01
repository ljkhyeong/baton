package com.personal.baton.adapter.out.external.watch;

import com.personal.baton.application.watch.WatchMonitorDelivery;
import com.personal.baton.application.watch.port.out.WatchMonitorClient;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.http.client.HttpRedirects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

public final class RestClientWatchMonitorClient implements WatchMonitorClient {

    private static final String MONITOR_PATH = "/api/v1/resource-monitors/{resourceReference}";

    private final RestClient restClient;

    RestClientWatchMonitorClient(RestClient restClient) {
        this.restClient = Objects.requireNonNull(restClient, "WATCH RestClient는 필수입니다");
    }

    public static RestClientWatchMonitorClient create(
            URI baseUri,
            String bearerToken,
            Duration connectTimeout,
            Duration readTimeout
    ) {
        Objects.requireNonNull(baseUri, "WATCH base URI는 필수입니다");
        Objects.requireNonNull(bearerToken, "WATCH bearer token은 필수입니다");
        HttpClientSettings settings = HttpClientSettings.defaults()
                .withTimeouts(
                        Objects.requireNonNull(connectTimeout, "WATCH connect timeout은 필수입니다"),
                        Objects.requireNonNull(readTimeout, "WATCH read timeout은 필수입니다")
                )
                .withRedirects(HttpRedirects.DONT_FOLLOW);
        ClientHttpRequestFactory requestFactory = ClientHttpRequestFactoryBuilder
                .detect()
                .build(settings);
        RestClient restClient = RestClient.builder()
                .baseUrl(baseUri)
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                .build();
        return new RestClientWatchMonitorClient(restClient);
    }

    @Override
    public SynchronizationResult synchronize(WatchMonitorDelivery delivery) {
        Objects.requireNonNull(delivery, "WATCH delivery는 필수입니다");
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
        if (status.is2xxSuccessful()) {
            return SynchronizationResult.delivered();
        }
        if (status.value() == 409) {
            if ("STALE_SOURCE_REVISION".equals(problemCode)) {
                return SynchronizationResult.stale();
            }
            if ("SOURCE_REVISION_CONFLICT".equals(problemCode)) {
                return SynchronizationResult.permanentFailure(problemCode);
            }
            return SynchronizationResult.permanentFailure(httpCode(status));
        }
        if (status.value() == 422) {
            if ("INVALID_TARGET_URL".equals(problemCode)) {
                return SynchronizationResult.invalidTarget();
            }
            return SynchronizationResult.permanentFailure(httpCode(status));
        }
        if (status.value() == 429 || status.is5xxServerError()) {
            return SynchronizationResult.retryable(httpCode(status));
        }
        return SynchronizationResult.permanentFailure(httpCode(status));
    }

    private String readKnownProblemCode(
            RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response
    ) throws IOException {
        if (response.getStatusCode().value() != 409
                && response.getStatusCode().value() != 422) {
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
}
