package com.personal.baton.adapter.out.external.calendar;

import com.personal.baton.application.calendar.CalendarSeasonMetadata;
import com.personal.baton.application.calendar.CalendarSnapshot;
import com.personal.baton.application.calendar.CalendarRecoveryManifest;
import com.personal.baton.application.calendar.port.out.CalendarRecoveryClient;
import com.personal.baton.application.calendar.port.out.CalendarSeasonMetadataClient;
import com.personal.baton.application.calendar.port.out.CalendarSnapshotClient;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
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

public final class RestClientCalendarClient implements
        CalendarSnapshotClient,
        CalendarSeasonMetadataClient,
        CalendarRecoveryClient {

    private static final String SNAPSHOT_PATH = "/internal/api/v1/schedule-snapshots";
    private static final Set<String> DELIVERED_RESULTS = Set.of(
            "APPLIED",
            "DUPLICATE",
            "STALE"
    );

    private final RestClient restClient;

    RestClientCalendarClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public DeliveryResult deliver(CalendarSnapshot snapshot) {
        return send(
                restClient.post()
                        .uri(SNAPSHOT_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .body(CalendarSnapshotRequest.from(snapshot)),
                response -> {
                    CalendarSnapshotResponse body = response.bodyTo(CalendarSnapshotResponse.class);
                    return body != null && body.result() != null && DELIVERED_RESULTS.contains(body.result())
                            ? DeliveryResult.delivered(body.result())
                            : invalidSuccess();
                }
        );
    }

    @Override
    public DeliveryResult deliver(CalendarSeasonMetadata metadata) {
        return send(
                restClient.put()
                        .uri("/internal/api/v1/seasons/{seasonId}/calendar-metadata", metadata.seasonId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .body(CalendarSeasonMetadataRequest.from(metadata)),
                response -> {
                    CalendarSeasonMetadataResponse body = response.bodyTo(CalendarSeasonMetadataResponse.class);
                    if (body == null || !metadata.seasonId().equals(body.seasonId())
                            || body.revision() == null || body.revision() < metadata.revision()
                            || body.displayName() == null || body.displayName().isEmpty()) {
                        return invalidSuccess();
                    }
                    if (body.revision() > metadata.revision()) {
                        return DeliveryResult.delivered("STALE");
                    }
                    return metadata.displayName().equals(body.displayName())
                            ? DeliveryResult.delivered("SEASON_METADATA_ACCEPTED")
                            : invalidSuccess();
                }
        );
    }

    @Override
    public DeliveryResult verifySeason(UUID recoveryId, CalendarRecoveryManifest.Season season) {
        return send(
                restClient.put()
                        .uri(
                                "/internal/api/v1/recovery-runs/{recoveryId}/seasons/{seasonId}/manifest",
                                recoveryId,
                                season.seasonId()
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .body(CalendarRecoverySeasonManifestRequest.from(season)),
                response -> {
                    CalendarRecoverySeasonManifestResponse body = response.bodyTo(
                            CalendarRecoverySeasonManifestResponse.class
                    );
                    return body != null
                            && recoveryId.equals(body.recoveryId())
                            && season.seasonId().equals(body.seasonId())
                            && "VERIFIED".equals(body.result())
                            && body.itemCount() == season.itemCount()
                            && Integer.valueOf(season.metadataRevision()).equals(body.metadataRevision())
                            ? DeliveryResult.delivered("RECOVERY_SEASON_VERIFIED")
                            : invalidSuccess();
                }
        );
    }

    @Override
    public DeliveryResult complete(UUID recoveryId, CalendarRecoveryManifest manifest) {
        return send(
                restClient.put()
                        .uri("/internal/api/v1/recovery-runs/{recoveryId}/completion", recoveryId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .body(CalendarRecoveryCompletionRequest.from(manifest)),
                response -> {
                    CalendarRecoveryCompletionResponse body = response.bodyTo(
                            CalendarRecoveryCompletionResponse.class
                    );
                    return body != null
                            && recoveryId.equals(body.recoveryId())
                            && "COMPLETED".equals(body.result())
                            && body.seasonCount() == manifest.seasons().size()
                            && body.completedAt() != null
                            ? DeliveryResult.delivered("RECOVERY_COMPLETED")
                            : invalidSuccess();
                }
        );
    }

    private DeliveryResult send(
            RestClient.RequestHeadersSpec<?> request,
            Function<RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse, DeliveryResult> readSuccess
    ) {
        try {
            return request.exchange((ignoredRequest, response) -> classify(response, readSuccess));
        } catch (ResourceAccessException exception) {
            return DeliveryResult.retryable("CAL_NETWORK_FAILURE");
        } catch (RestClientException exception) {
            return DeliveryResult.retryable("CAL_CLIENT_FAILURE");
        }
    }

    private DeliveryResult classify(
            RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response,
            Function<RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse, DeliveryResult> readSuccess
    ) throws IOException {
        HttpStatusCode status = response.getStatusCode();
        if (status.isSameCodeAs(HttpStatus.OK)) {
            try {
                return readSuccess.apply(response);
            } catch (RestClientException exception) {
                if (exception.getMostSpecificCause() instanceof IOException) {
                    return DeliveryResult.retryable("CAL_NETWORK_FAILURE");
                }
                return invalidSuccess();
            }
        }
        if (status.isSameCodeAs(HttpStatus.UNAUTHORIZED)
                || status.isSameCodeAs(HttpStatus.FORBIDDEN)
                || status.isSameCodeAs(HttpStatus.TOO_MANY_REQUESTS)
                || status.is5xxServerError()) {
            return DeliveryResult.retryable(httpCode(status));
        }
        String code = errorCode(response, status);
        return "RECOVERY_MANIFEST_MISMATCH".equals(code)
                ? DeliveryResult.retryable(code)
                : DeliveryResult.permanentFailure(code);
    }

    private DeliveryResult invalidSuccess() {
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

    private record CalendarSeasonMetadataResponse(UUID seasonId, Integer revision, String displayName) {
    }

    private record CalendarRecoverySeasonManifestResponse(
            UUID recoveryId,
            UUID seasonId,
            String result,
            int itemCount,
            Integer metadataRevision
    ) {
    }

    private record CalendarRecoveryCompletionResponse(
            UUID recoveryId,
            String result,
            int seasonCount,
            Instant completedAt
    ) {
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

        public RestClientCalendarClient create(
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
            return new RestClientCalendarClient(restClient);
        }
    }
}
