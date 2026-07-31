package com.personal.baton.adapter.out.persistence.workspace;

import com.personal.baton.domain.workspace.RoleResource;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RoleResourceJpaRepository extends JpaRepository<RoleResource, UUID> {

    List<RoleResource> findAllByRoleIdInOrderByRoleIdAscIdAsc(List<UUID> roleIds);

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select resource from RoleResource resource where resource.id = :resourceId")
    Optional<RoleResource> findByIdWithSharedLock(@Param("resourceId") UUID resourceId);

    @Query("""
            select role.teamId as teamId,
                   role.seasonId as seasonId,
                   resource.id as resourceId,
                   resource.url as resourceUrl
            from RoleResource resource, Role role, Season season,
                 MemberIdentityBinding binding, Member member
            where resource.roleId = role.id
              and season.id = role.seasonId
              and season.teamId = role.teamId
              and binding.teamId = role.teamId
              and binding.userAccountId = :accountId
              and member.id = binding.memberId
              and member.teamId = binding.teamId
              and member.deactivatedAt is null
              and resource.url = :resourceUrl
            order by role.teamId, role.seasonId, resource.id
            """)
    List<AuthorizedRoundResourceProjection> findAuthorizedRoundResources(
            @Param("accountId") UUID accountId,
            @Param("resourceUrl") String resourceUrl
    );

    interface AuthorizedRoundResourceProjection {

        UUID getTeamId();

        UUID getSeasonId();

        UUID getResourceId();

        String getResourceUrl();
    }
}
