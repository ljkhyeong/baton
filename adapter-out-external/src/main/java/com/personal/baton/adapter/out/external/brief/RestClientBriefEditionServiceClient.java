package com.personal.baton.adapter.out.external.brief;

import com.personal.baton.application.brief.BriefEditionSnapshot;
import com.personal.baton.application.brief.port.out.BriefEditionServiceClient;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

public final class RestClientBriefEditionServiceClient
        implements BriefEditionServiceClient {

    private final RestClient restClient;

    public RestClientBriefEditionServiceClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public Result findLatestEdition(UUID workspaceId, UUID seasonId) {
        try {
            ResponseEntity<BriefEditionSnapshot> response = restClient.get()
                    .uri(
                            "/api/v1/workspaces/{workspaceId}/seasons/{seasonId}/editions/latest",
                            workspaceId,
                            seasonId
                    )
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .toEntity(BriefEditionSnapshot.class);
            return completed(response, false);
        } catch (RestClientResponseException exception) {
            return failure(exception.getStatusCode(), true);
        } catch (ResourceAccessException exception) {
            return Result.failure(Outcome.RETRYABLE_FAILURE, "BRIEF_NETWORK_FAILURE");
        } catch (RestClientException exception) {
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
            return completed(response, response.getStatusCode().value() == 201);
        } catch (RestClientResponseException exception) {
            return failure(exception.getStatusCode(), false);
        } catch (ResourceAccessException exception) {
            return Result.failure(Outcome.RETRYABLE_FAILURE, "BRIEF_NETWORK_FAILURE");
        } catch (RestClientException exception) {
            return Result.failure(Outcome.PERMANENT_FAILURE, "BRIEF_RESPONSE_FAILURE");
        }
    }

    private Result completed(
            ResponseEntity<BriefEditionSnapshot> response,
            boolean created
    ) {
        BriefEditionSnapshot body = response.getBody();
        String etag = response.getHeaders().getFirst(HttpHeaders.ETAG);
        if (body == null || etag == null || etag.isBlank()) {
            return Result.failure(Outcome.PERMANENT_FAILURE, "BRIEF_RESPONSE_INVALID");
        }
        return Result.completed(body, etag, created);
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
