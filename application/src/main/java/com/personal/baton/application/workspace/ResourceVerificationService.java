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
import java.util.UUID;
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
    public VerificationHistoryResult getHistory(UUID teamId, UUID seasonId, UUID resourceId, String accessKey) {
        authorizer.authorizeRead(teamId, seasonId, accessKey);
        return history(teamId, seasonId, requireResource(teamId, seasonId, resourceId));
    }

    @Override
    @Transactional
    public VerificationHistoryResult verify(UUID teamId, UUID seasonId, UUID resourceId, String accessKey,
            UUID accountId, VerifyResourceCommand command) {
        authorizer.authorizeSeasonForUpdate(teamId, seasonId, accessKey);
        RoleResource resource = requireResource(teamId, seasonId, resourceId);
        if (resource.getArchivedAt() != null || resource.getVersion() != command.resourceVersion()) {
            throw new WorkspaceContentConflictException();
        }
        Member member = memberships.findMembership(accountId, teamId)
                .flatMap(membership -> people.findMemberById(membership.getMemberId()))
                .filter(found -> found.getTeamId().equals(teamId)).filter(Member::isActive)
                .orElseThrow(WorkspaceAccessDeniedException::new);
        verifications.save(ResourceVerification.create(resource, accountId, member, command.status(),
                command.note(), clock.instant()));
        return history(teamId, seasonId, resource);
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
