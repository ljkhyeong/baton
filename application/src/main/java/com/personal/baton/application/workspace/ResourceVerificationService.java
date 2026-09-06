package com.personal.baton.application.workspace;

import com.personal.baton.application.roundauth.port.out.RoundAuthorizationRepository;
import com.personal.baton.application.workspace.error.WorkspaceAccessDeniedException;
import com.personal.baton.application.workspace.error.WorkspaceContentConflictException;
import com.personal.baton.application.workspace.error.WorkspaceNotFoundException;
import com.personal.baton.application.workspace.port.in.ResourceVerificationUseCase;
import com.personal.baton.application.workspace.port.out.ResourceVerificationRepository;
import com.personal.baton.application.workspace.port.out.WorkspacePeopleRepository;
import com.personal.baton.application.workspace.port.out.WorkspaceRecordsRepository;
import com.personal.baton.domain.workspace.Member;
import com.personal.baton.domain.workspace.ResourceVerification;
import com.personal.baton.domain.workspace.RoleResource;
import java.time.Clock;
import java.time.LocalDate;
import com.personal.baton.domain.workspace.ResourceReviewSchedule;
import com.personal.baton.domain.workspace.ResourceVerificationStatus;
import java.util.UUID;
import java.util.List;
import java.util.Optional;
import java.util.Comparator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ResourceVerificationService implements ResourceVerificationUseCase {
    private final WorkspaceScopeAuthorizer authorizer;
    private final WorkspaceRoleResolver roleResolver;
    private final WorkspaceRecordsRepository records;
    private final WorkspacePeopleRepository people;
    private final RoundAuthorizationRepository memberships;
    private final ResourceVerificationRepository verifications;
    private final Clock clock;

    public ResourceVerificationService(WorkspaceScopeAuthorizer authorizer, WorkspaceRoleResolver roleResolver,
            WorkspaceRecordsRepository records, WorkspacePeopleRepository people,
            RoundAuthorizationRepository memberships, ResourceVerificationRepository verifications, Clock clock) {
        this.authorizer = authorizer;
        this.roleResolver = roleResolver;
        this.records = records;
        this.people = people;
        this.memberships = memberships;
        this.verifications = verifications;
        this.clock = clock;
    }

    @Override
    public DueReviewsResult getDueReviews(UUID teamId, UUID seasonId, String accessKey) {
        var scope = authorizer.authorizeRead(teamId, seasonId, accessKey);
        var today = LocalDate.ofInstant(clock.instant(), scope.season().getZoneId());
        if (scope.season().getEndedAt() != null) {
            return new DueReviewsResult(teamId, seasonId, today, scope.season().getZoneId().getId(), List.of());
        }
        var due = verifications.findDueReviews(teamId, seasonId, today).stream()
                .map(value -> new DueReviewResult(value.getResourceId(), value.getRoleId(), value.getTitle(),
                        value.getRoleName(), value.getMemberId(), value.getMemberName(), value.getNextReviewOn()))
                .sorted(Comparator.comparing(DueReviewResult::nextReviewOn).thenComparing(DueReviewResult::resourceId))
                .toList();
        return new DueReviewsResult(teamId, seasonId, today, scope.season().getZoneId().getId(), due);
    }

    @Override
    public ReviewScheduleResult getSchedule(UUID teamId, UUID seasonId, UUID resourceId, String accessKey) {
        var scope = authorizer.authorizeRead(teamId, seasonId, accessKey);
        requireResource(teamId, seasonId, resourceId);
        return scheduleResult(teamId, seasonId, resourceId, verifications.findSchedule(resourceId),
                LocalDate.ofInstant(clock.instant(), scope.season().getZoneId()));
    }

    @Override
    @Transactional
    public ReviewScheduleResult configureSchedule(UUID teamId, UUID seasonId, UUID resourceId, String accessKey,
            UUID accountId, ConfigureReviewScheduleCommand command) {
        var scope = authorizer.authorizeSeasonForUpdate(teamId, seasonId, accessKey);
        if (requireResource(teamId, seasonId, resourceId).getArchivedAt() != null) throw new WorkspaceContentConflictException();
        requireMember(teamId, accountId);
        var existing = verifications.findSchedule(resourceId);
        if (existing.map(ResourceReviewSchedule::getVersion).orElse(-1L) != command.expectedVersion()) throw new WorkspaceContentConflictException();
        var schedule = existing.orElseGet(() -> ResourceReviewSchedule.create(resourceId));
        schedule.configure(command.intervalDays(), command.nextReviewOn());
        var saved = verifications.saveSchedule(schedule);
        return scheduleResult(teamId, seasonId, resourceId, Optional.of(saved),
                LocalDate.ofInstant(clock.instant(), scope.season().getZoneId()));
    }

    private ReviewScheduleResult scheduleResult(UUID teamId, UUID seasonId, UUID resourceId,
            Optional<ResourceReviewSchedule> schedule, LocalDate today) {
        return new ReviewScheduleResult(teamId, seasonId, resourceId, schedule.map(ResourceReviewSchedule::getVersion).orElse(-1L),
                schedule.map(ResourceReviewSchedule::getIntervalDays).orElse(null), schedule.map(ResourceReviewSchedule::getNextReviewOn).orElse(null),
                today, schedule.map(value -> value.isDueOn(today)).orElse(false));
    }

    @Override
    public VerificationHistoryResult getHistory(UUID teamId, UUID seasonId, UUID resourceId, String accessKey) {
        authorizer.authorizeRead(teamId, seasonId, accessKey);
        return history(teamId, seasonId, requireResource(teamId, seasonId, resourceId));
    }

    @Override
    @Transactional
    public VerificationHistoryResult verify(UUID teamId, UUID seasonId, UUID resourceId, String accessKey,
            UUID accountId, VerifyResourceCommand command) {
        var scope = authorizer.authorizeSeasonForUpdate(teamId, seasonId, accessKey);
        RoleResource resource = requireResource(teamId, seasonId, resourceId);
        if (resource.getArchivedAt() != null || resource.getVersion() != command.resourceVersion()) {
            throw new WorkspaceContentConflictException();
        }
        Member member = requireMember(teamId, accountId);
        verifications.save(ResourceVerification.create(resource, accountId, member, command.status(),
                command.note(), clock.instant()));
        if (command.status() == ResourceVerificationStatus.CONFIRMED) {
            var schedule = verifications.findSchedule(resourceId).orElse(null);
            if (schedule != null) {
                schedule.confirmOn(LocalDate.ofInstant(clock.instant(), scope.season().getZoneId()));
                verifications.saveSchedule(schedule);
            }
        }
        return history(teamId, seasonId, resource);
    }

    private Member requireMember(UUID teamId, UUID accountId) {
        return memberships.findMembership(accountId, teamId)
                .flatMap(membership -> people.findMemberById(membership.getMemberId()))
                .filter(found -> found.getTeamId().equals(teamId)).filter(Member::isActive)
                .orElseThrow(WorkspaceAccessDeniedException::new);
    }

    private RoleResource requireResource(UUID teamId, UUID seasonId, UUID resourceId) {
        RoleResource resource = records.findRoleResourceById(resourceId)
                .orElseThrow(() -> new WorkspaceNotFoundException("ROLE_RESOURCE_NOT_FOUND", "자료를 찾을 수 없습니다"));
        roleResolver.requireRole(teamId, seasonId, resource.getRoleId());
        return resource;
    }

    private VerificationHistoryResult history(UUID teamId, UUID seasonId, RoleResource resource) {
        return new VerificationHistoryResult(teamId, seasonId, resource.getId(), resource.getVersion(),
                verifications.findRecent(resource.getId()).stream().map(value -> new VerificationResult(
                        value.getId(), value.getResourceVersion(), value.getMemberId(), value.getMemberName(),
                        value.getUrl(), value.getStatus(), value.getNote(), value.getVerifiedAt(),
                        value.getResourceVersion() == resource.getVersion())).toList());
    }
}
