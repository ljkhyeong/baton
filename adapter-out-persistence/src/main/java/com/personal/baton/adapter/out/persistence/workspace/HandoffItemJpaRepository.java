package com.personal.baton.adapter.out.persistence.workspace;

import com.personal.baton.domain.workspace.HandoffItem;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HandoffItemJpaRepository extends JpaRepository<HandoffItem, UUID> {

    List<HandoffItem> findAllByRoleIdInOrderByIdAsc(List<UUID> roleIds);
}
