package com.personal.baton.adapter.out.persistence.roundauth;

import com.personal.baton.domain.roundauth.RoundRoomTombstone;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface RoundRoomTombstoneJpaRepository
        extends JpaRepository<RoundRoomTombstone, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<RoundRoomTombstone> findForUpdateByRoomId(String roomId);

    @Lock(LockModeType.PESSIMISTIC_READ)
    Optional<RoundRoomTombstone> findForShareByRoomId(String roomId);
}
