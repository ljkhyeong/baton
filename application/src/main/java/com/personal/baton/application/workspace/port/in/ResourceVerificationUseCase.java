package com.personal.baton.application.workspace.port.in;

import com.personal.baton.domain.workspace.ResourceVerificationStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface ResourceVerificationUseCase {
    DueReviewsResult getDueReviews(UUID teamId, UUID seasonId, String accessKey);
    record DueReviewResult(UUID resourceId, UUID roleId, String title, String roleName,
            UUID memberId, String memberName, LocalDate nextReviewOn) {}
    record DueReviewsResult(UUID teamId, UUID seasonId, LocalDate today, String timeZone,
            List<DueReviewResult> resources) {}
    ReviewScheduleResult getSchedule(UUID teamId, UUID seasonId, UUID resourceId, String accessKey);
    ReviewScheduleResult configureSchedule(UUID teamId, UUID seasonId, UUID resourceId, String accessKey,
            UUID accountId, ConfigureReviewScheduleCommand command);
    record ConfigureReviewScheduleCommand(long expectedVersion, Integer intervalDays, LocalDate nextReviewOn) {}
    record ReviewScheduleResult(UUID teamId, UUID seasonId, UUID resourceId, long version,
            Integer intervalDays, LocalDate nextReviewOn, LocalDate today, boolean reviewDue) {}
    VerificationHistoryResult getHistory(UUID teamId, UUID seasonId, UUID resourceId, String accessKey);
    VerificationHistoryResult verify(UUID teamId, UUID seasonId, UUID resourceId, String accessKey,
            UUID accountId, VerifyResourceCommand command);

    record VerifyResourceCommand(long resourceVersion, ResourceVerificationStatus status, String note) {}
    record VerificationResult(UUID id, long resourceVersion, UUID memberId, String memberName,
            String url, ResourceVerificationStatus status, String note, Instant verifiedAt, boolean current) {}
    record VerificationHistoryResult(UUID teamId, UUID seasonId, UUID resourceId, long resourceVersion,
            List<VerificationResult> verifications) {}
}
