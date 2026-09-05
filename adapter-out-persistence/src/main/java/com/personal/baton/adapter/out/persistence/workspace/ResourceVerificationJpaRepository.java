package com.personal.baton.adapter.out.persistence.workspace;

import com.personal.baton.domain.workspace.ResourceVerification;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ResourceVerificationJpaRepository extends JpaRepository<ResourceVerification, UUID> {
    List<ResourceVerification> findTop20ByResourceIdOrderByVerifiedAtDescIdDesc(UUID resourceId);
}
