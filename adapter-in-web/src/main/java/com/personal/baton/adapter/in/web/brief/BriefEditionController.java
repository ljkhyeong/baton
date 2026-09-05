package com.personal.baton.adapter.in.web.brief;

import com.personal.baton.adapter.in.web.auth.AuthenticatedAccountPrincipal;
import com.personal.baton.adapter.in.web.brief.BriefEditionResponses.BriefEditionGenerationResponse;
import com.personal.baton.adapter.in.web.brief.BriefEditionResponses.BriefEditionResponse;
import com.personal.baton.application.brief.port.in.BriefEditionUseCase;
import com.personal.baton.application.brief.port.in.BriefEditionUseCase.GenerateEditionCommand;
import com.personal.baton.application.brief.port.in.BriefEditionUseCase.LatestEditionQuery;
import com.personal.baton.application.brief.BriefEditionHistory;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import org.springframework.web.bind.annotation.RequestParam;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

@RestController
public class BriefEditionController {

    public static final String GENERATION_PATH =
            "/api/v1/teams/{teamId}/seasons/{seasonId}/brief/editions";
    public static final String LATEST_PATH = GENERATION_PATH + "/latest";

    public static final String EDITION_PATH = GENERATION_PATH + "/{editionId}";
    public static final String COMPARISON_PATH = EDITION_PATH + "/changes";
    public static final String PREVIOUS_WEEK_PATH = EDITION_PATH + "/previous-week";
    public static final String DELIVERY_STATUS_PATH = EDITION_PATH + "/delivery-status";

    private static final String ACCESS_KEY_HEADER = "X-Baton-Access-Key";

    private final BriefEditionUseCase briefEditionUseCase;

    public BriefEditionController(BriefEditionUseCase briefEditionUseCase) {
        this.briefEditionUseCase = briefEditionUseCase;
    }

    @GetMapping(LATEST_PATH)
    public ResponseEntity<BriefEditionResponse> findLatestEdition(
            @PathVariable UUID teamId,
            @PathVariable UUID seasonId,
            @RequestHeader(value = ACCESS_KEY_HEADER, required = false) String accessKey,
            @AuthenticationPrincipal(errorOnInvalidType = true)
            AuthenticatedAccountPrincipal principal
    ) {
        var result = briefEditionUseCase.findLatestEdition(
                new LatestEditionQuery(
                        principal.accountId(),
                        teamId,
                        seasonId,
                        accessKey
                )
        );
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .eTag(result.etag())
                .body(BriefEditionResponse.from(result.edition()));
    }

    @GetMapping(PREVIOUS_WEEK_PATH)
    public ResponseEntity<BriefEditionResponse> previousWeek(
            @PathVariable UUID teamId, @PathVariable UUID seasonId, @PathVariable UUID editionId,
            @RequestHeader(value = ACCESS_KEY_HEADER, required = false) String accessKey,
            @AuthenticationPrincipal(errorOnInvalidType = true) AuthenticatedAccountPrincipal principal
    ) {
        var result = briefEditionUseCase.findPreviousWeekEdition(
                new LatestEditionQuery(principal.accountId(), teamId, seasonId, accessKey), editionId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).eTag(result.etag())
                .body(BriefEditionResponse.from(result.edition()));
    }

    @GetMapping(DELIVERY_STATUS_PATH)
    public ResponseEntity<BriefEditionResponses.DeliveryStatusResponse> deliveryStatus(
            @PathVariable UUID teamId, @PathVariable UUID seasonId, @PathVariable UUID editionId,
            @RequestHeader(value = ACCESS_KEY_HEADER, required = false) String accessKey,
            @AuthenticationPrincipal(errorOnInvalidType = true) AuthenticatedAccountPrincipal principal
    ) {
        var result = briefEditionUseCase.findEditionDeliveryStatus(
                new LatestEditionQuery(principal.accountId(), teamId, seasonId, accessKey), editionId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new BriefEditionResponses.DeliveryStatusResponse(result.editionId(), result.status(), result.checkedAt()));
    }

    @GetMapping(GENERATION_PATH)
    public ResponseEntity<BriefEditionResponses.HistoryResponse> history(
            @PathVariable UUID teamId, @PathVariable UUID seasonId,
            @RequestHeader(value = ACCESS_KEY_HEADER, required = false) String accessKey,
            @AuthenticationPrincipal(errorOnInvalidType = true) AuthenticatedAccountPrincipal principal,
            @RequestParam(required = false) @Min(1) Long beforeGeneration,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit
    ) {
        var result = briefEditionUseCase.findEditionHistory(
                new LatestEditionQuery(principal.accountId(), teamId, seasonId, accessKey),
                new BriefEditionHistory.Query(beforeGeneration, limit));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(BriefEditionResponses.HistoryResponse.from(result));
    }

    @GetMapping(EDITION_PATH)
    public ResponseEntity<BriefEditionResponse> edition(
            @PathVariable UUID teamId, @PathVariable UUID seasonId, @PathVariable UUID editionId,
            @RequestHeader(value = ACCESS_KEY_HEADER, required = false) String accessKey,
            @AuthenticationPrincipal(errorOnInvalidType = true) AuthenticatedAccountPrincipal principal
    ) {
        var result = briefEditionUseCase.findEdition(new LatestEditionQuery(principal.accountId(), teamId, seasonId, accessKey), editionId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).eTag(result.etag())
                .body(BriefEditionResponse.from(result.edition()));
    }

    @GetMapping(COMPARISON_PATH)
    public ResponseEntity<BriefEditionResponses.ComparisonResponse> compare(
            @PathVariable UUID teamId, @PathVariable UUID seasonId, @PathVariable UUID editionId,
            @RequestHeader(value = ACCESS_KEY_HEADER, required = false) String accessKey,
            @AuthenticationPrincipal(errorOnInvalidType = true) AuthenticatedAccountPrincipal principal,
            @RequestParam UUID fromEditionId
    ) {
        var result = briefEditionUseCase.compareEditions(new LatestEditionQuery(principal.accountId(), teamId, seasonId, accessKey),
                fromEditionId, editionId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(BriefEditionResponses.ComparisonResponse.from(result));
    }

    @PostMapping(GENERATION_PATH)
    public ResponseEntity<BriefEditionGenerationResponse> generateEdition(
            @PathVariable UUID teamId,
            @PathVariable UUID seasonId,
            @RequestHeader(value = ACCESS_KEY_HEADER, required = false) String accessKey,
            @AuthenticationPrincipal(errorOnInvalidType = true)
            AuthenticatedAccountPrincipal principal
    ) {
        var result = briefEditionUseCase.generateEdition(
                new GenerateEditionCommand(
                        principal.accountId(),
                        teamId,
                        seasonId,
                        accessKey
                )
        );
        ResponseEntity.BodyBuilder response = ResponseEntity
                .status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .cacheControl(CacheControl.noStore())
                .eTag(result.etag());
        if (result.created()) {
            response.location(UriComponentsBuilder.fromPath(LATEST_PATH)
                    .buildAndExpand(teamId, seasonId)
                    .toUri());
        }
        return response.body(BriefEditionGenerationResponse.from(result));
    }
}
