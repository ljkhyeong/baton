package com.personal.baton.application.workspace;

import com.personal.baton.application.workspace.port.in.VerifyWorkspaceAccessUseCase;
import com.personal.baton.domain.workspace.Season;
import com.personal.baton.application.workspace.error.WorkspaceAccessDeniedException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class WorkspaceAccessService implements VerifyWorkspaceAccessUseCase {

    private final WorkspaceScopeAuthorizer scopeAuthorizer;

    public WorkspaceAccessService(WorkspaceScopeAuthorizer scopeAuthorizer) {
        this.scopeAuthorizer = scopeAuthorizer;
    }

    @Override
    public void verifyTeamRead(UUID teamId, String accessKey) {
        scopeAuthorizer.authorizeTeamRead(teamId, accessKey);
    }

    @Override
    public Season verifyRead(UUID teamId, UUID seasonId, String accessKey) {
        return scopeAuthorizer.authorizeRead(teamId, seasonId, accessKey).season();
    }

    @Override
    public void verifyMembershipClaim(UUID teamId, UUID seasonId, String accessKey) {
        WorkspaceScope scope = scopeAuthorizer.authorizeMutation(teamId, seasonId, accessKey);
        if (scope.team().isAccountAccessEnabled()) throw new WorkspaceAccessDeniedException();
    }

    @Override
    public Season verifyMutation(UUID teamId, UUID seasonId, String accessKey) {
        return scopeAuthorizer.authorizeMutation(teamId, seasonId, accessKey).season();
    }
}
