package com.personal.baton.adapter.out.external.brief;

import com.personal.baton.application.brief.BriefEditionSnapshot;
import com.personal.baton.application.brief.BriefAttentionPage;
import com.personal.baton.application.brief.BriefAttentionSummary;
import com.personal.baton.application.brief.BriefAttentionTransitions;
import com.personal.baton.application.brief.error.BriefAttentionQueryRejectedException;
import com.personal.baton.application.brief.error.BriefIntegrationConfigurationException;
import com.personal.baton.application.brief.error.BriefIntegrationUnavailableException;
import com.personal.baton.application.brief.port.out.BriefServiceClient;
import java.io.IOException;
import com.personal.baton.application.brief.BriefEditionHistory;
import com.personal.baton.application.brief.BriefEditionComparison;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

public final class RestClientBriefServiceClient
        implements BriefServiceClient {

    private final RestClient restClient;

    public RestClientBriefServiceClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public BriefAttentionSummary summarizeAttention(UUID workspaceId, UUID seasonId) {
        BriefAttentionSummary summary = readQuery(() -> restClient.get()
                .uri("/api/v1/workspaces/{workspaceId}/seasons/{seasonId}/attention-items/summary",
                        workspaceId, seasonId)
                .accept(MediaType.APPLICATION_JSON).retrieve().toEntity(BriefAttentionSummary.class), false);
        if (summary.highCount() == null || summary.highCount() < 0
                || summary.mediumCount() == null || summary.mediumCount() < 0
                || summary.revisionGapCount() == null || summary.revisionGapCount() < 0) {
            throw new BriefIntegrationConfigurationException();
        }
        return summary;
    }

    @Override
    public BriefAttentionPage findAttentionItems(
            UUID workspaceId, UUID seasonId, BriefAttentionPage.Filter filter
    ) {
        BriefAttentionPage page = readQuery(() -> restClient.get()
                .uri(builder -> {
                    builder.path("/api/v1/workspaces/{workspaceId}/seasons/{seasonId}/attention-items")
                            .queryParam("status", filter.status())
                            .queryParam("limit", filter.limit())
                            .queryParamIfPresent("severity", Optional.ofNullable(filter.severity()))
                            .queryParamIfPresent("revisionGap", Optional.ofNullable(filter.revisionGap()));
                    if (filter.after() != null) {
                        builder.queryParam("afterEventType", filter.after().eventType())
                                .queryParam("afterSourceReference", "{sourceReference}");
                        return builder.build(workspaceId, seasonId, filter.after().sourceReference());
                    }
                    return builder.build(workspaceId, seasonId);
                })
                .accept(MediaType.APPLICATION_JSON).retrieve().toEntity(BriefAttentionPage.class), true);
        if (page.items() == null || page.items().stream().anyMatch(item -> item == null
                || item.reasonCode() == null || item.severity() == null
                || item.sourceReference() == null || item.sourceReference().isBlank()
                || item.status() == null || item.observedAt() == null
                || item.aggregateRevision() == null || item.aggregateRevision() < 1
                || item.ruleVersion() == null || item.ruleVersion() < 1
                || item.revisionGap() == null)
                || (page.nextCursor() != null && (page.nextCursor().eventType() == null
                || page.nextCursor().sourceReference() == null
                || page.nextCursor().sourceReference().isBlank()))) {
            throw new BriefIntegrationConfigurationException();
        }
        return page;
    }

    @Override
    public BriefAttentionTransitions findAttentionTransitions(
            UUID workspaceId, UUID seasonId, BriefAttentionTransitions.Query query
    ) {
        BriefAttentionTransitions history = readQuery(() -> restClient.get()
                .uri(builder -> builder
                        .path("/api/v1/workspaces/{workspaceId}/seasons/{seasonId}/attention-items/transitions")
                        .queryParam("eventType", query.eventType())
                        .queryParam("sourceReference", "{sourceReference}")
                        .queryParam("limit", query.limit())
                        .queryParamIfPresent("beforeAggregateRevision", Optional.ofNullable(query.beforeAggregateRevision()))
                        .build(workspaceId, seasonId, query.sourceReference()))
                .accept(MediaType.APPLICATION_JSON).retrieve().toEntity(BriefAttentionTransitions.class), true);
        if (history.transitions() == null || history.transitions().stream().anyMatch(transition -> transition == null
                || transition.eventId() == null || transition.aggregateRevision() == null || transition.aggregateRevision() < 1
                || transition.state() == null || transition.observedAt() == null || transition.detectedRevisionGap() == null)
                || (history.nextBeforeAggregateRevision() != null && history.nextBeforeAggregateRevision() < 1)) {
            throw new BriefIntegrationConfigurationException();
        }
        return history;
    }

    private <T> T readQuery(Supplier<ResponseEntity<T>> request, boolean hasFilter) {
        try {
            ResponseEntity<T> response = request.get();
            if (response.getStatusCode().value() != 200 || response.getBody() == null) {
                throw new BriefIntegrationConfigurationException();
            }
            return response.getBody();
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            if (hasFilter && status == 400) {
                throw new BriefAttentionQueryRejectedException();
            }
            if (status == 429 || exception.getStatusCode().is5xxServerError()) {
                throw new BriefIntegrationUnavailableException();
            }
            throw new BriefIntegrationConfigurationException();
        } catch (ResourceAccessException exception) {
            throw new BriefIntegrationUnavailableException();
        } catch (RestClientException exception) {
            if (exception.getMostSpecificCause() instanceof IOException) {
                throw new BriefIntegrationUnavailableException();
            }
            throw new BriefIntegrationConfigurationException();
        }
    }

    @Override
    public Result findLatestEdition(UUID workspaceId, UUID seasonId) {
        return readEdition(() -> restClient.get()
                .uri("/api/v1/workspaces/{workspaceId}/seasons/{seasonId}/editions/latest", workspaceId, seasonId)
                .accept(MediaType.APPLICATION_JSON).retrieve().toEntity(BriefEditionSnapshot.class));
    }

    @Override
    public Result findEdition(UUID editionId) {
        return readEdition(() -> restClient.get().uri("/api/v1/editions/{editionId}", editionId)
                .accept(MediaType.APPLICATION_JSON).retrieve().toEntity(BriefEditionSnapshot.class));
    }

    @Override
    public BriefEditionHistory findEditionHistory(UUID workspaceId, UUID seasonId, BriefEditionHistory.Query query) {
        var history = readQuery(() -> restClient.get().uri(builder -> builder
                .path("/api/v1/workspaces/{workspaceId}/seasons/{seasonId}/editions")
                .queryParam("limit", query.limit())
                .queryParamIfPresent("beforeGeneration", Optional.ofNullable(query.beforeGeneration()))
                .build(workspaceId, seasonId)).accept(MediaType.APPLICATION_JSON)
                .retrieve().toEntity(BriefEditionHistory.class), false);
        if (history.editions() == null || history.editions().stream().anyMatch(summary -> !isValid(summary))
                || (history.nextBeforeGeneration() != null && history.nextBeforeGeneration() < 1)) {
            throw new BriefIntegrationConfigurationException();
        }
        return history;
    }

    @Override
    public BriefEditionComparison compareEditions(UUID fromEditionId, UUID toEditionId) {
        var comparison = readQuery(() -> restClient.get().uri(builder -> builder
                .path("/api/v1/editions/{editionId}/changes").queryParam("fromEditionId", fromEditionId)
                .build(toEditionId)).accept(MediaType.APPLICATION_JSON)
                .retrieve().toEntity(BriefEditionComparison.class), false);
        if (!isValid(comparison.from()) || !isValid(comparison.to())
                || !fromEditionId.equals(comparison.from().editionId()) || !toEditionId.equals(comparison.to().editionId())
                || comparison.added() == null || comparison.added().stream().anyMatch(item -> !isValid(item))
                || comparison.removed() == null || comparison.removed().stream().anyMatch(item -> !isValid(item))
                || comparison.changed() == null || comparison.changed().stream().anyMatch(change -> change == null
                    || !isValid(change.before()) || !isValid(change.after())
                    || !change.before().sourceReference().equals(change.after().sourceReference())
                    || !change.before().reasonCode().equals(change.after().reasonCode()))) {
            throw new BriefIntegrationConfigurationException();
        }
        return comparison;
    }

    private boolean isValid(BriefEditionHistory.Summary summary) {
        return summary != null && summary.editionId() != null && summary.generation() > 0
                && summary.weekStart() != null && summary.zoneId() != null && summary.generatedAt() != null
                && summary.sourceCursor() >= 0 && summary.ruleVersion() > 0 && summary.itemCount() >= 0;
    }

    private Result readEdition(Supplier<ResponseEntity<BriefEditionSnapshot>> request) {
        try {
            ResponseEntity<BriefEditionSnapshot> response = request.get();
            return response.getStatusCode().value() == 200
                    ? completed(response, false)
                    : invalidResponse();
        } catch (RestClientResponseException exception) {
            return failure(exception.getStatusCode(), true);
        } catch (ResourceAccessException exception) {
            return Result.failure(Outcome.RETRYABLE_FAILURE, "BRIEF_NETWORK_FAILURE");
        } catch (RestClientException exception) {
            if (exception.getMostSpecificCause() instanceof IOException) {
                return Result.failure(Outcome.RETRYABLE_FAILURE, "BRIEF_NETWORK_FAILURE");
            }
            return Result.failure(Outcome.PERMANENT_FAILURE, "BRIEF_RESPONSE_FAILURE");
        }
    }

    @Override
    public Result generateEdition(
            UUID workspaceId,
            UUID seasonId,
            LocalDate weekStart,
            ZoneId zoneId
    ) {
        try {
            ResponseEntity<BriefEditionSnapshot> response = restClient.post()
                    .uri(
                            "/api/v1/workspaces/{workspaceId}/seasons/{seasonId}/editions",
                            workspaceId,
                            seasonId
                    )
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(new EditionWeekRequest(weekStart, zoneId))
                    .retrieve()
                    .toEntity(BriefEditionSnapshot.class);
            int status = response.getStatusCode().value();
            return status == 200 || status == 201
                    ? completed(response, status == 201)
                    : invalidResponse();
        } catch (RestClientResponseException exception) {
            return failure(exception.getStatusCode(), false);
        } catch (ResourceAccessException exception) {
            return Result.failure(Outcome.RETRYABLE_FAILURE, "BRIEF_NETWORK_FAILURE");
        } catch (RestClientException exception) {
            if (exception.getMostSpecificCause() instanceof IOException) {
                return Result.failure(Outcome.RETRYABLE_FAILURE, "BRIEF_NETWORK_FAILURE");
            }
            return Result.failure(Outcome.PERMANENT_FAILURE, "BRIEF_RESPONSE_FAILURE");
        }
    }

    private Result completed(
            ResponseEntity<BriefEditionSnapshot> response,
            boolean created
    ) {
        BriefEditionSnapshot body = response.getBody();
        String etag = response.getHeaders().getFirst(HttpHeaders.ETAG);
        if (!isValid(body) || etag == null || etag.isBlank()) {
            return invalidResponse();
        }
        return Result.completed(body, etag, created);
    }

    private boolean isValid(BriefEditionSnapshot edition) {
        return edition != null
                && edition.editionId() != null
                && edition.workspaceId() != null
                && edition.seasonId() != null
                && edition.generation() > 0
                && edition.weekStart() != null
                && edition.zoneId() != null
                && edition.windowStart() != null
                && edition.windowEnd() != null
                && edition.sourceCursor() >= 0
                && edition.generatedAt() != null
                && edition.ruleVersion() > 0
                && edition.items() != null
                && edition.items().stream().allMatch(this::isValid);
    }

    private boolean isValid(BriefEditionSnapshot.Item item) {
        return item != null
                && StringUtils.hasText(item.sourceReference())
                && StringUtils.hasText(item.reasonCode())
                && StringUtils.hasText(item.severity())
                && StringUtils.hasText(item.status())
                && item.observedAt() != null
                && item.ruleVersion() > 0;
    }

    private Result invalidResponse() {
        return Result.failure(Outcome.PERMANENT_FAILURE, "BRIEF_RESPONSE_INVALID");
    }

    private Result failure(HttpStatusCode status, boolean query) {
        int code = status.value();
        if (query && code == 404) {
            return Result.failure(Outcome.NOT_FOUND, "HTTP_404");
        }
        if (code == 400) {
            return Result.failure(Outcome.INVALID_REQUEST, "HTTP_400");
        }
        if (code == 401 || code == 403) {
            return Result.failure(Outcome.AUTHENTICATION_FAILURE, "HTTP_" + code);
        }
        if (code == 429 || status.is5xxServerError()) {
            return Result.failure(Outcome.RETRYABLE_FAILURE, "HTTP_" + code);
        }
        return Result.failure(Outcome.PERMANENT_FAILURE, "HTTP_" + code);
    }

    private record EditionWeekRequest(LocalDate weekStart, ZoneId zoneId) {
    }
}
