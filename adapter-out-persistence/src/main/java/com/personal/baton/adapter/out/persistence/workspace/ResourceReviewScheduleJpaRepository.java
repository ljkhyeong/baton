package com.personal.baton.adapter.out.persistence.workspace;

import com.personal.baton.domain.workspace.ResourceReviewSchedule;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ResourceReviewScheduleJpaRepository extends JpaRepository<ResourceReviewSchedule, UUID> {}
