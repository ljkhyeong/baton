package com.personal.baton.adapter.out.persistence.roundauth;

import com.personal.baton.domain.roundauth.RoundRoomTombstone;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoundRoomTombstoneJpaRepository
        extends JpaRepository<RoundRoomTombstone, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select tombstone from RoundRoomTombstone tombstone where tombstone.roomId = :roomId")
    Optional<RoundRoomTombstone> findByRoomIdForUpdate(@Param("roomId") String roomId);

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select tombstone from RoundRoomTombstone tombstone where tombstone.roomId = :roomId")
    Optional<RoundRoomTombstone> findByRoomIdForShare(@Param("roomId") String roomId);
}
