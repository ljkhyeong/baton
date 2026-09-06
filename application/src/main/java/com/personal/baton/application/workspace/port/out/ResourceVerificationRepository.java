package com.personal.baton.application.workspace.port.out;

import com.personal.baton.domain.workspace.ResourceVerification;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import com.personal.baton.domain.workspace.ResourceReviewSchedule;
import java.util.UUID;

public interface ResourceVerificationRepository {
    List<DueReview> findDueReviews(UUID teamId, UUID seasonId, LocalDate today);

    interface DueReview {
        UUID getResourceId();
        UUID getRoleId();
        String getTitle();
        String getRoleName();
        UUID getMemberId();
        String getMemberName();
        LocalDate getNextReviewOn();
    }

    Optional<ResourceReviewSchedule> findSchedule(UUID resourceId);
    ResourceReviewSchedule saveSchedule(ResourceReviewSchedule schedule);
    ResourceVerification save(ResourceVerification verification);
    List<ResourceVerification> findRecent(UUID resourceId);
}
