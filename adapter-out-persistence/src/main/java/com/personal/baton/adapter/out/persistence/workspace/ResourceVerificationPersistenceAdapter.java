package com.personal.baton.adapter.out.persistence.workspace;

import com.personal.baton.application.workspace.port.out.ResourceVerificationRepository;
import com.personal.baton.domain.workspace.ResourceVerification;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class ResourceVerificationPersistenceAdapter implements ResourceVerificationRepository {
    private final ResourceVerificationJpaRepository repository;
    public ResourceVerificationPersistenceAdapter(ResourceVerificationJpaRepository repository) {
        this.repository = repository;
    }
    @Override
    public ResourceVerification save(ResourceVerification verification) { return repository.save(verification); }
    @Override
    public List<ResourceVerification> findRecent(UUID resourceId) {
        return repository.findTop20ByResourceIdOrderByVerifiedAtDescIdDesc(resourceId);
    }
}
