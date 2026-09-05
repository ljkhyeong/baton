package com.personal.baton.application.workspace.port.out;

import com.personal.baton.domain.workspace.ResourceVerification;
import java.util.List;
import java.util.UUID;

public interface ResourceVerificationRepository {
    ResourceVerification save(ResourceVerification verification);
    List<ResourceVerification> findRecent(UUID resourceId);
}
