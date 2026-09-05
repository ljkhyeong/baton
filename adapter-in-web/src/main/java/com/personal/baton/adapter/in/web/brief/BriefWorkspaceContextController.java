package com.personal.baton.adapter.in.web.brief;

import com.personal.baton.adapter.in.web.auth.AuthenticatedAccountPrincipal;
import com.personal.baton.application.brief.BriefAttentionPage;
import com.personal.baton.application.brief.BriefSourceContext;
import com.personal.baton.application.brief.BriefGenerationReadiness;
import com.personal.baton.application.brief.port.in.BriefAttentionUseCase.Scope;
import com.personal.baton.application.brief.port.in.BriefWorkspaceContextUseCase;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BriefWorkspaceContextController {
    public static final String SOURCES_PATH = "/api/v1/teams/{teamId}/seasons/{seasonId}/brief/sources/query";
    public static final String READINESS_PATH = "/api/v1/teams/{teamId}/seasons/{seasonId}/brief/generation-readiness";
    private final BriefWorkspaceContextUseCase useCase;

    public BriefWorkspaceContextController(BriefWorkspaceContextUseCase useCase) { this.useCase = useCase; }

    public record SourceRequest(@NotNull BriefAttentionPage.EventType eventType, @NotBlank @Size(max = 512) String sourceReference) { }
    public record SourcesRequest(@NotNull @Size(min = 1, max = 100) List<@NotNull @Valid SourceRequest> sources) { }
    public record TargetResponse(String title, UUID roleId, UUID routineId, boolean archived) { }
    public record SourceResponse(BriefAttentionPage.EventType eventType, String sourceReference, TargetResponse target) { }
    public record SourcesResponse(List<SourceResponse> sources) { }
    public record ReadinessResponse(BriefGenerationReadiness.Status status, long pendingCount, long failedCount,
                                    Instant lastDeliveredAt, Instant checkedAt) { }

    @PostMapping(SOURCES_PATH)
    public ResponseEntity<SourcesResponse> sources(
            @PathVariable UUID teamId, @PathVariable UUID seasonId,
            @RequestHeader(value = "X-Baton-Access-Key", required = false) String accessKey,
            @AuthenticationPrincipal(errorOnInvalidType = true) AuthenticatedAccountPrincipal principal,
            @Valid @RequestBody SourcesRequest request
    ) {
        var sources = useCase.resolveSources(new Scope(principal.accountId(), teamId, seasonId, accessKey), request.sources().stream()
                .map(source -> new BriefSourceContext.Identity(source.eventType(), source.sourceReference())).toList());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new SourcesResponse(sources.stream().map(source -> {
            var target = source.target();
            return new SourceResponse(source.identity().eventType(), source.identity().sourceReference(), target == null ? null
                    : new TargetResponse(target.title(), target.roleId(), target.routineId(), target.archived()));
        }).toList()));
    }

    @GetMapping(READINESS_PATH)
    public ResponseEntity<ReadinessResponse> readiness(
            @PathVariable UUID teamId, @PathVariable UUID seasonId,
            @RequestHeader(value = "X-Baton-Access-Key", required = false) String accessKey,
            @AuthenticationPrincipal(errorOnInvalidType = true) AuthenticatedAccountPrincipal principal
    ) {
        var result = useCase.findGenerationReadiness(new Scope(principal.accountId(), teamId, seasonId, accessKey));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new ReadinessResponse(result.status(),
                result.pendingCount(), result.failedCount(), result.lastDeliveredAt(), result.checkedAt()));
    }
}
