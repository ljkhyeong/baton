package com.personal.baton.adapter.out.persistence.workspace;

import com.personal.baton.domain.workspace.RoleResource;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleResourceJpaRepository extends JpaRepository<RoleResource, UUID> {

    List<RoleResource> findAllByRoleIdInOrderByRoleIdAscIdAsc(List<UUID> roleIds);
}
