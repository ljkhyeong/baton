package com.personal.baton.adapter.out.persistence.roundauth;

import com.personal.baton.domain.roundauth.RoundRoomMapping;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoundRoomMappingJpaRepository extends JpaRepository<RoundRoomMapping, UUID> {

    Optional<RoundRoomMapping> findByRoomId(String roomId);

    Optional<RoundRoomMapping> findByResourceId(UUID resourceId);
}
