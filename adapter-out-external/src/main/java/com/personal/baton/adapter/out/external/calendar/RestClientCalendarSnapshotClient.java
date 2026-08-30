package com.personal.baton.adapter.out.external.calendar;

import com.personal.baton.application.calendar.CalendarSnapshot;
import com.personal.baton.application.calendar.port.out.CalendarSnapshotClient;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Set;
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

public final class RestClientCalendarSnapshotClient implements CalendarSnapshotClient {

    private static final String SNAPSHOT_PATH = "/internal/api/v1/schedule-snapshots";
    private static final Set<String> DELIVERED_RESULTS = Set.of(
            "APPLIED",
            "DUPLICATE",
            "STALE"
    );

    private final RestClient restClient;

    RestClientCalendarSnapshotClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public DeliveryResult deliver(CalendarSnapshot snapshot) {
        try {
            return restClient.post()
                    .uri(SNAPSHOT_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(CalendarSnapshotRequest.from(snapshot))
                    .exchange((ignoredRequest, response) -> classify(
                            response.getStatusCode(),
                            response
                    ));
        } catch (ResourceAccessException exception) {
            return DeliveryResult.retryable("CAL_NETWORK_FAILURE");
        } catch (RestClientException exception) {
            return DeliveryResult.retryable("CAL_CLIENT_FAILURE");
        }
    }

    private DeliveryResult classify(
            HttpStatusCode status,
            RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response
    ) {
        if (status.isSameCodeAs(HttpStatus.OK)) {
            return success(response);
        }
        if (status.isSameCodeAs(HttpStatus.UNAUTHORIZED)
                || status.isSameCodeAs(HttpStatus.FORBIDDEN)
                || status.isSameCodeAs(HttpStatus.TOO_MANY_REQUESTS)
                || status.is5xxServerError()) {
            return DeliveryResult.retryable(httpCode(status));
        }
        return DeliveryResult.permanentFailure(errorCode(response, status));
    }

    private DeliveryResult success(
            RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response
    ) {
        try {
            CalendarSnapshotResponse body = response.bodyTo(CalendarSnapshotResponse.class);
            if (body != null && body.result() != null && DELIVERED_RESULTS.contains(body.result())) {
                return DeliveryResult.delivered(body.result());
            }
        } catch (RestClientException exception) {
            if (exception.getMostSpecificCause() instanceof IOException) {
                return DeliveryResult.retryable("CAL_NETWORK_FAILURE");
            }
        }
        return DeliveryResult.permanentFailure("CAL_INVALID_SUCCESS_RESPONSE");
    }

    private String errorCode(
            RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response,
            HttpStatusCode status
    ) {
        try {
            CalendarErrorResponse body = response.bodyTo(CalendarErrorResponse.class);
            if (body != null && body.code() != null && !body.code().isBlank()) {
                return body.code();
            }
        } catch (RuntimeException ignored) {
            // 안정적인 오류 코드가 없으면 HTTP 상태만 보존한다.
        }
        return httpCode(status);
    }

    private String httpCode(HttpStatusCode status) {
        return "HTTP_" + status.value();
    }

    private record CalendarSnapshotResponse(String result) {
    }

    private record CalendarErrorResponse(String code) {
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

        public RestClientCalendarSnapshotClient create(
                URI baseUri,
                String bearerToken,
                Duration connectTimeout,
                Duration readTimeout
        ) {
            HttpClientSettings settings = managedHttpClientSettings
                    .withTimeouts(connectTimeout, readTimeout)
                    .withRedirects(HttpRedirects.DONT_FOLLOW);
            ClientHttpRequestFactory requestFactory = requestFactoryBuilder.build(settings);
            RestClient restClient = restClientBuilder.clone()
                    .baseUrl(baseUri)
                    .requestFactory(requestFactory)
                    .defaultHeaders(headers -> headers.setBearerAuth(bearerToken))
                    .build();
            return new RestClientCalendarSnapshotClient(restClient);
        }
    }
}
