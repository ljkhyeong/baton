package com.personal.baton.adapter.in.web.workspace;

import com.personal.baton.application.workspace.port.in.ResourceVerificationUseCase.DueReviewsResult;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ResourceDueReviewsResponse(UUID teamId, UUID seasonId, LocalDate today, String timeZone,
        List<ResourceDueReviewResponse> resources) {
    public record ResourceDueReviewResponse(UUID resourceId, UUID roleId, String title, String roleName,
            UUID memberId, String memberName, LocalDate nextReviewOn) {}
    static ResourceDueReviewsResponse from(DueReviewsResult result) {
        return new ResourceDueReviewsResponse(result.teamId(), result.seasonId(), result.today(), result.timeZone(),
                result.resources().stream().map(value -> new ResourceDueReviewResponse(value.resourceId(), value.roleId(),
                        value.title(), value.roleName(), value.memberId(), value.memberName(), value.nextReviewOn())).toList());
    }
}
