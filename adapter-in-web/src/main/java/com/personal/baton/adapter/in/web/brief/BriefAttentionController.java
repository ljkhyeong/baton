package com.personal.baton.adapter.in.web.brief;

import com.personal.baton.adapter.in.web.auth.AuthenticatedAccountPrincipal;
import com.personal.baton.adapter.in.web.brief.BriefAttentionResponses.PageResponse;
import com.personal.baton.adapter.in.web.brief.BriefAttentionResponses.SummaryResponse;
import com.personal.baton.application.brief.BriefAttentionPage;
import com.personal.baton.application.brief.BriefAttentionTransitions;
import com.personal.baton.application.brief.port.in.BriefAttentionUseCase;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BriefAttentionController {
    public static final String LIST_PATH = "/api/v1/teams/{teamId}/seasons/{seasonId}/brief/attention-items";
    public static final String SUMMARY_PATH = LIST_PATH + "/summary";
    public static final String TRANSITIONS_PATH = LIST_PATH + "/transitions";

    private final BriefAttentionUseCase useCase;

    public BriefAttentionController(BriefAttentionUseCase useCase) {
        this.useCase = useCase;
    }

    @GetMapping(SUMMARY_PATH)
    public ResponseEntity<SummaryResponse> summarize(
            @PathVariable UUID teamId, @PathVariable UUID seasonId,
            @RequestHeader("X-Baton-Access-Key") String accessKey,
            @AuthenticationPrincipal(errorOnInvalidType = true) AuthenticatedAccountPrincipal principal
    ) {
        var summary = useCase.summarizeAttention(new BriefAttentionUseCase.Scope(
                principal.accountId(), teamId, seasonId, accessKey));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(SummaryResponse.from(summary));
    }

    @GetMapping(LIST_PATH)
    public ResponseEntity<PageResponse> list(
            @PathVariable UUID teamId, @PathVariable UUID seasonId,
            @RequestHeader("X-Baton-Access-Key") String accessKey,
            @AuthenticationPrincipal(errorOnInvalidType = true) AuthenticatedAccountPrincipal principal,
            @RequestParam(defaultValue = "ACTIVE") BriefAttentionPage.Status status,
            @RequestParam(required = false) BriefAttentionPage.Severity severity,
            @RequestParam(required = false) Boolean revisionGap,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
            @Valid @ModelAttribute BriefAttentionCursorRequest cursor
    ) {
        var page = useCase.findAttentionItems(new BriefAttentionUseCase.Scope(
                        principal.accountId(), teamId, seasonId, accessKey),
                new BriefAttentionPage.Filter(status, severity, revisionGap, cursor.toCursor(), limit));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(PageResponse.from(page));
    }

    @GetMapping(TRANSITIONS_PATH)
    public ResponseEntity<BriefAttentionResponses.TransitionsResponse> transitions(
            @PathVariable UUID teamId, @PathVariable UUID seasonId,
            @RequestHeader("X-Baton-Access-Key") String accessKey,
            @AuthenticationPrincipal(errorOnInvalidType = true) AuthenticatedAccountPrincipal principal,
            @RequestParam BriefAttentionPage.EventType eventType,
            @RequestParam String sourceReference,
            @RequestParam(required = false) @Min(1) Long beforeAggregateRevision,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit
    ) {
        var history = useCase.findAttentionTransitions(new BriefAttentionUseCase.Scope(
                        principal.accountId(), teamId, seasonId, accessKey),
                new BriefAttentionTransitions.Query(eventType, sourceReference, beforeAggregateRevision, limit));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(BriefAttentionResponses.TransitionsResponse.from(history));
    }
}
