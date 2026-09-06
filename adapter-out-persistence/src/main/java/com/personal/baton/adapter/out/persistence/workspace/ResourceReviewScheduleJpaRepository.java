package com.personal.baton.adapter.out.persistence.workspace;

import com.personal.baton.domain.workspace.ResourceReviewSchedule;
import com.personal.baton.application.workspace.port.out.ResourceVerificationRepository.DueReview;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ResourceReviewScheduleJpaRepository extends JpaRepository<ResourceReviewSchedule, UUID> {
    @Query("""
            select resource.id as resourceId, role.id as roleId, resource.title as title,
                   role.name as roleName, member.id as memberId, member.name as memberName,
                   schedule.nextReviewOn as nextReviewOn
            from ResourceReviewSchedule schedule
            join RoleResource resource on resource.id = schedule.resourceId
            join Role role on role.id = resource.roleId
            left join Member member on member.id = role.currentMemberId
                and member.teamId = :teamId and member.deactivatedAt is null
            where role.teamId = :teamId and role.seasonId = :seasonId
                and resource.archivedAt is null and schedule.nextReviewOn <= :today
            """)
    List<DueReview> findDueReviews(UUID teamId, UUID seasonId, LocalDate today);
}
