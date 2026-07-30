package com.personal.baton.adapter.out.persistence.workspace;

import com.personal.baton.application.round.port.out.RoundGrantResourceRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class RoundGrantResourcePersistenceAdapter
        implements RoundGrantResourceRepository {

    private final RoleResourceJpaRepository roleResourceRepository;

    public RoundGrantResourcePersistenceAdapter(
            RoleResourceJpaRepository roleResourceRepository
    ) {
        this.roleResourceRepository = roleResourceRepository;
    }

    @Override
    public List<AuthorizedRoundResource> findAuthorizedResourcesByAccountIdAndUrl(
            UUID accountId,
            String resourceUrl
    ) {
        return roleResourceRepository.findAuthorizedRoundResources(
                        accountId,
                        resourceUrl
                )
                .stream()
                .map(candidate -> new AuthorizedRoundResource(
                        candidate.getTeamId(),
                        candidate.getSeasonId(),
                        candidate.getResourceId(),
                        candidate.getResourceUrl()
                ))
                .toList();
    }
}
