package com.personal.baton.adapter.out.external.calendar;

import com.personal.baton.application.calendar.CalendarSeasonMetadata;
import com.personal.baton.application.calendar.CalendarSnapshot;
import com.personal.baton.application.calendar.CalendarRecoveryManifest;
import com.personal.baton.application.calendar.port.out.CalendarRecoveryClient;
import com.personal.baton.application.calendar.port.out.CalendarSubscriptionClient;
import com.personal.baton.application.calendar.port.out.CalendarSeasonMetadataClient;
import com.personal.baton.application.calendar.port.out.CalendarSnapshotClient;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.Optional;
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
        CalendarRecoveryClient,
        CalendarSubscriptionClient {

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
    public Optional<RecoveryRun> findRecoveryRun(UUID recoveryId) {
        return diagnostic(restClient.get().uri("/internal/api/v1/recovery-runs/{id}", recoveryId), response -> {
            var body = response.bodyTo(RecoveryRunResponse.class);
            if (body == null || !recoveryId.equals(body.recoveryId()) || body.status() == null
                    || body.recoveryMode() == null || body.verifiedSeasonCount() == null || body.verifiedSeasonCount() < 0
                    || (body.status() == RunStatus.COMPLETED) != (body.completedAt() != null)) return null;
            return new RecoveryRun(recoveryId, body.status(), body.recoveryMode(), body.verifiedSeasonCount(), body.completedAt());
        });
    }

    @Override
    public Optional<SeasonState> findRecoverySeason(UUID seasonId) {
        return diagnostic(restClient.get().uri("/internal/api/v1/seasons/{id}/recovery-state", seasonId), response -> {
            var body = response.bodyTo(RecoverySeasonResponse.class);
            if (body == null || !seasonId.equals(body.seasonId()) || body.itemCount() == null || body.itemCount() < 0
                    || body.itemDigest() == null || !body.itemDigest().matches("[0-9a-f]{64}")
                    || (body.metadataRevision() == null) != (body.metadataDigest() == null)
                    || (body.metadataRevision() != null && (body.metadataRevision() < 0 || !body.metadataDigest().matches("[0-9a-f]{64}")))) return null;
            return new SeasonState(seasonId, body.itemCount(), body.itemDigest(), body.metadataRevision(), body.metadataDigest());
        });
    }

    private <T> Optional<T> diagnostic(RestClient.RequestHeadersSpec<?> request,
            Function<RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse, T> read) {
        try {
            return request.exchange((ignored, response) -> response.getStatusCode().isSameCodeAs(HttpStatus.OK)
                    ? Optional.ofNullable(read.apply(response)) : Optional.empty());
        } catch (RestClientException | IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private record RecoveryRunResponse(UUID recoveryId, RunStatus status, Boolean recoveryMode, Integer verifiedSeasonCount, Instant completedAt) {}
    private record RecoverySeasonResponse(UUID seasonId, Integer itemCount, String itemDigest, Integer metadataRevision, String metadataDigest) {}

    @Override
    public Result create(UUID subscriptionId, UUID seasonId) {
        return subscriptionRequest(restClient.put()
                .uri("/internal/api/v1/subscriptions/{id}", subscriptionId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new SubscriptionCreateRequest(seasonId)), HttpStatus.CREATED,
                response -> credential(response, subscriptionId));
    }

    @Override
    public Result findSubscription(UUID subscriptionId) {
        return subscriptionRequest(restClient.get().uri("/internal/api/v1/subscriptions/{id}", subscriptionId),
                HttpStatus.OK, response -> {
                    SubscriptionStatusResponse body = response.bodyTo(SubscriptionStatusResponse.class);
                    if (body == null || !subscriptionId.equals(body.subscriptionId()) || body.seasonId() == null
                            || !Set.of("ACTIVE", "REVOKED").contains(body.status() == null ? "" : body.status())
                            || body.generationMatches() == null) return Result.of(CalendarSubscriptionClient.Outcome.INVALID_RESPONSE);
                    return new Result(CalendarSubscriptionClient.Outcome.SUCCESS, null, new RemoteStatus(body.subscriptionId(), body.seasonId(),
                            "REVOKED".equals(body.status()), body.generationMatches()));
                });
    }

    @Override
    public Result rotate(UUID subscriptionId) {
        return subscriptionRequest(restClient.post().uri("/internal/api/v1/subscriptions/{id}/rotate", subscriptionId),
                HttpStatus.OK, response -> credential(response, subscriptionId));
    }

    @Override
    public Result revoke(UUID subscriptionId) {
        return subscriptionRequest(restClient.delete().uri("/internal/api/v1/subscriptions/{id}", subscriptionId),
                HttpStatus.NO_CONTENT, response -> Result.of(CalendarSubscriptionClient.Outcome.SUCCESS));
    }

    private Result credential(RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response, UUID subscriptionId) {
        SubscriptionCredentialResponse body = response.bodyTo(SubscriptionCredentialResponse.class);
        if (body == null || !subscriptionId.equals(body.subscriptionId()) || body.token() == null
                || !body.token().matches("[A-Za-z0-9_-]{43}") || body.feedUrl() == null) return Result.of(CalendarSubscriptionClient.Outcome.INVALID_RESPONSE);
        URI uri = body.feedUrl();
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null
                || !uri.getRawPath().endsWith("/calendars/v1/" + body.token() + ".ics")) return Result.of(CalendarSubscriptionClient.Outcome.INVALID_RESPONSE);
        return new Result(CalendarSubscriptionClient.Outcome.SUCCESS, new Credential(subscriptionId, uri), null);
    }

    private Result subscriptionRequest(RestClient.RequestHeadersSpec<?> request, HttpStatus success,
            Function<RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse, Result> read) {
        try {
            return request.exchange((ignored, response) -> {
                HttpStatusCode status = response.getStatusCode();
                if (status.isSameCodeAs(success)) return read.apply(response);
                if (status.isSameCodeAs(HttpStatus.NOT_FOUND)) return Result.of(CalendarSubscriptionClient.Outcome.NOT_FOUND);
                if (status.isSameCodeAs(HttpStatus.CONFLICT)) {
                    return Result.of("SUBSCRIPTION_ALREADY_EXISTS".equals(errorCode(response, status))
                            ? CalendarSubscriptionClient.Outcome.ALREADY_EXISTS : CalendarSubscriptionClient.Outcome.INVALID_RESPONSE);
                }
                return Result.of(status.is4xxClientError() || status.is5xxServerError()
                        ? CalendarSubscriptionClient.Outcome.UNAVAILABLE : CalendarSubscriptionClient.Outcome.INVALID_RESPONSE);
            });
        } catch (ResourceAccessException exception) {
            return Result.of(CalendarSubscriptionClient.Outcome.UNAVAILABLE);
        } catch (RestClientException exception) {
            return Result.of(exception.getMostSpecificCause() instanceof IOException
                    ? CalendarSubscriptionClient.Outcome.UNAVAILABLE : CalendarSubscriptionClient.Outcome.INVALID_RESPONSE);
        } catch (IllegalArgumentException exception) {
            return Result.of(CalendarSubscriptionClient.Outcome.INVALID_RESPONSE);
        }
    }

    private record SubscriptionCreateRequest(UUID seasonId) {}
    private record SubscriptionStatusResponse(UUID subscriptionId, UUID seasonId, String status, Boolean generationMatches) {}
    private record SubscriptionCredentialResponse(UUID subscriptionId, String token, URI feedUrl) {
        @Override public String toString() { return "SubscriptionCredentialResponse[<redacted>]"; }
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
