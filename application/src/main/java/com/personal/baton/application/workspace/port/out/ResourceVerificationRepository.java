package com.personal.baton.application.workspace.port.out;

import com.personal.baton.domain.workspace.ResourceVerification;
import java.util.List;
import java.util.Optional;
import com.personal.baton.domain.workspace.ResourceReviewSchedule;
import java.util.UUID;

public interface ResourceVerificationRepository {
    List<ResourceReviewSchedule> findSchedules(List<UUID> resourceIds);
    Optional<ResourceReviewSchedule> findSchedule(UUID resourceId);
    ResourceReviewSchedule saveSchedule(ResourceReviewSchedule schedule);
    ResourceVerification save(ResourceVerification verification);
    List<ResourceVerification> findRecent(UUID resourceId);
}
