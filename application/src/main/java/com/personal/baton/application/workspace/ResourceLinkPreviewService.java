package com.personal.baton.application.workspace;

import com.personal.baton.application.workspace.port.in.ResourceLinkPreviewUseCase;
import com.personal.baton.application.workspace.port.out.ResourceLinkPreviewPort;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ResourceLinkPreviewService implements ResourceLinkPreviewUseCase {
    private final WorkspaceScopeAuthorizer authorizer;
    private final ResourceLinkPreviewPort previews;

    ResourceLinkPreviewService(WorkspaceScopeAuthorizer authorizer, ResourceLinkPreviewPort previews) {
        this.authorizer = authorizer;
        this.previews = previews;
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Preview preview(UUID teamId, UUID seasonId, String accessKey, String url) {
        authorizer.authorizeRead(teamId, seasonId, accessKey);
        return previews.preview(url);
    }
}
