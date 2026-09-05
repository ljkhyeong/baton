package com.personal.baton.adapter.in.web.workspace;

import com.personal.baton.application.workspace.port.in.ResourceVerificationUseCase.ReviewScheduleResult;
import java.time.LocalDate;
import java.util.UUID;

public record ResourceReviewScheduleResponse(UUID teamId, UUID seasonId, UUID resourceId, long version,
        Integer intervalDays, LocalDate nextReviewOn, LocalDate today, boolean reviewDue) {
    static ResourceReviewScheduleResponse from(ReviewScheduleResult result) {
        return new ResourceReviewScheduleResponse(result.teamId(), result.seasonId(), result.resourceId(), result.version(),
                result.intervalDays(), result.nextReviewOn(), result.today(), result.reviewDue());
    }
}
