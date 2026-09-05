package com.personal.baton.adapter.in.web.workspace;

import com.personal.baton.adapter.in.web.auth.AuthenticatedAccountPrincipal;
import com.personal.baton.application.roundauth.error.AccountMembershipConflictException;
import com.personal.baton.application.workspace.port.in.ResourceVerificationUseCase;
import com.personal.baton.application.workspace.port.in.ResourceVerificationUseCase.VerifyResourceCommand;
import com.personal.baton.application.workspace.port.in.ResourceVerificationUseCase.VerificationHistoryResult;
import com.personal.baton.domain.workspace.ResourceVerificationStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
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
public class ResourceVerificationController {
    public static final String PATH = "/api/v1/teams/{teamId}/seasons/{seasonId}/role-resources/{resourceId}/verifications";
    public static final String SCHEDULE_PATH = PATH + "/schedule";
    public static final String DUE_PATH = "/api/v1/teams/{teamId}/seasons/{seasonId}/resource-reviews";
    private final ResourceVerificationUseCase useCase;
    public ResourceVerificationController(ResourceVerificationUseCase useCase) { this.useCase = useCase; }

    @GetMapping(DUE_PATH)
    public ResponseEntity<ResourceDueReviewsResponse> dueReviews(@PathVariable UUID teamId, @PathVariable UUID seasonId,
            @RequestHeader(value = "X-Baton-Access-Key", required = false) String accessKey) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ResourceDueReviewsResponse.from(useCase.getDueReviews(teamId, seasonId, accessKey)));
    }

    @GetMapping(SCHEDULE_PATH)
    public ResponseEntity<ResourceReviewScheduleResponse> schedule(@PathVariable UUID teamId, @PathVariable UUID seasonId,
            @PathVariable UUID resourceId, @RequestHeader(value = "X-Baton-Access-Key", required = false) String accessKey) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ResourceReviewScheduleResponse.from(useCase.getSchedule(teamId, seasonId, resourceId, accessKey)));
    }
    @PostMapping(SCHEDULE_PATH)
    public ResponseEntity<ResourceReviewScheduleResponse> configureSchedule(@PathVariable UUID teamId, @PathVariable UUID seasonId,
            @PathVariable UUID resourceId, @RequestHeader(value = "X-Baton-Access-Key", required = false) String accessKey,
            @AuthenticationPrincipal(errorOnInvalidType = true) AuthenticatedAccountPrincipal principal,
            @Valid @RequestBody ResourceReviewScheduleRequest request) {
        if (!principal.accountId().equals(request.expectedAccountId())) throw new AccountMembershipConflictException("로그인 계정이 변경되었습니다. 새로고침해 주세요.");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ResourceReviewScheduleResponse.from(
                useCase.configureSchedule(teamId, seasonId, resourceId, accessKey, principal.accountId(),
                        new ResourceVerificationUseCase.ConfigureReviewScheduleCommand(request.expectedVersion(), request.intervalDays(), request.nextReviewOn()))));
    }

    @GetMapping(PATH)
    public ResponseEntity<ResourceVerificationHistoryResponse> history(@PathVariable UUID teamId,
            @PathVariable UUID seasonId, @PathVariable UUID resourceId,
            @RequestHeader(value = "X-Baton-Access-Key", required = false) String accessKey) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ResourceVerificationHistoryResponse.from(useCase.getHistory(teamId, seasonId, resourceId, accessKey)));
    }

    @PostMapping(PATH)
    public ResponseEntity<ResourceVerificationHistoryResponse> verify(@PathVariable UUID teamId,
            @PathVariable UUID seasonId, @PathVariable UUID resourceId,
            @RequestHeader(value = "X-Baton-Access-Key", required = false) String accessKey,
            @AuthenticationPrincipal(errorOnInvalidType = true) AuthenticatedAccountPrincipal principal,
            @Valid @RequestBody VerifyResourceRequest request) {
        if (!principal.accountId().equals(request.expectedAccountId())) {
            throw new AccountMembershipConflictException("로그인 계정이 변경되었습니다. 새로고침한 뒤 다시 확인해 주세요.");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ResourceVerificationHistoryResponse.from(
                useCase.verify(teamId, seasonId, resourceId, accessKey, principal.accountId(),
                        new VerifyResourceCommand(request.resourceVersion(), request.status(), request.note()))));
    }

    public record VerifyResourceRequest(@NotNull UUID expectedAccountId,
            @NotNull @PositiveOrZero Long resourceVersion, @NotNull ResourceVerificationStatus status,
            @Size(max = 500) String note) {}
    public record ResourceVerificationResponse(UUID id, long resourceVersion, UUID memberId, String memberName,
            String url, ResourceVerificationStatus status, String note, Instant verifiedAt, boolean current) {}
    public record ResourceVerificationHistoryResponse(UUID teamId, UUID seasonId, UUID resourceId,
            long resourceVersion, List<ResourceVerificationResponse> verifications) {
        static ResourceVerificationHistoryResponse from(VerificationHistoryResult result) {
            return new ResourceVerificationHistoryResponse(result.teamId(), result.seasonId(), result.resourceId(),
                    result.resourceVersion(), result.verifications().stream().map(value -> new ResourceVerificationResponse(
                            value.id(), value.resourceVersion(), value.memberId(), value.memberName(), value.url(),
                            value.status(), value.note(), value.verifiedAt(),
                            value.current())).toList());
        }
    }
}
