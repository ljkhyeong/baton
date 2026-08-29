package com.personal.baton.adapter.in.web.brief;

import com.personal.baton.adapter.in.web.auth.AuthenticatedAccountPrincipal;
import com.personal.baton.adapter.in.web.brief.BriefEditionResponses.BriefEditionGenerationResponse;
import com.personal.baton.adapter.in.web.brief.BriefEditionResponses.BriefEditionResponse;
import com.personal.baton.application.brief.port.in.BriefEditionUseCase;
import com.personal.baton.application.brief.port.in.BriefEditionUseCase.GenerateEditionCommand;
import com.personal.baton.application.brief.port.in.BriefEditionUseCase.LatestEditionQuery;
import java.net.URI;
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

    private static final String ACCESS_KEY_HEADER = "X-Baton-Access-Key";

    private final BriefEditionUseCase briefEditionUseCase;

    public BriefEditionController(BriefEditionUseCase briefEditionUseCase) {
        this.briefEditionUseCase = briefEditionUseCase;
    }

    @GetMapping(LATEST_PATH)
    public ResponseEntity<BriefEditionResponse> findLatestEdition(
            @PathVariable UUID teamId,
            @PathVariable UUID seasonId,
            @RequestHeader(ACCESS_KEY_HEADER) String accessKey,
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

    @PostMapping(GENERATION_PATH)
    public ResponseEntity<BriefEditionGenerationResponse> generateEdition(
            @PathVariable UUID teamId,
            @PathVariable UUID seasonId,
            @RequestHeader(ACCESS_KEY_HEADER) String accessKey,
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
