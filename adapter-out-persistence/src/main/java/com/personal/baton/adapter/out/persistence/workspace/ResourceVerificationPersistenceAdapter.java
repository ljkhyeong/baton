package com.personal.baton.adapter.out.persistence.workspace;

import com.personal.baton.application.workspace.port.out.ResourceVerificationRepository;
import com.personal.baton.domain.workspace.ResourceVerification;
import java.util.List;
import java.util.Optional;
import com.personal.baton.domain.workspace.ResourceReviewSchedule;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class ResourceVerificationPersistenceAdapter implements ResourceVerificationRepository {
    private final ResourceVerificationJpaRepository repository;
    private final ResourceReviewScheduleJpaRepository schedules;
    public ResourceVerificationPersistenceAdapter(ResourceVerificationJpaRepository repository, ResourceReviewScheduleJpaRepository schedules) {
        this.schedules = schedules;
        this.repository = repository;
    }
    @Override public Optional<ResourceReviewSchedule> findSchedule(UUID resourceId) { return schedules.findById(resourceId); }
    @Override public ResourceReviewSchedule saveSchedule(ResourceReviewSchedule schedule) { return schedules.saveAndFlush(schedule); }
    @Override
    public ResourceVerification save(ResourceVerification verification) { return repository.save(verification); }
    @Override
    public List<ResourceVerification> findRecent(UUID resourceId) {
        return repository.findTop20ByResourceIdOrderByVerifiedAtDescIdDesc(resourceId);
    }
}
