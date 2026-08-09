package com.personal.baton.application.roundauth.port.out;

import com.personal.baton.domain.roundauth.AccountTeamMembership;
import com.personal.baton.domain.roundauth.RoundRoomMapping;
import com.personal.baton.domain.roundauth.RoundRoomTombstone;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public interface RoundAuthorizationRepository {

    sealed interface RoomMappingCreationResult {

        record Created(RoundRoomMapping mapping) implements RoomMappingCreationResult {

            public Created {
                Objects.requireNonNull(mapping, "생성된 ROUND 방 매핑은 필수입니다");
            }
        }

        record RoomIdUnavailable() implements RoomMappingCreationResult {
        }

        record ResourceBecameAvailable() implements RoomMappingCreationResult {
        }

        record ResourceAlreadyMapped(RoundRoomMapping mapping)
                implements RoomMappingCreationResult {

            public ResourceAlreadyMapped {
                Objects.requireNonNull(mapping, "기존 ROUND 방 매핑은 필수입니다");
            }
        }
    }

    AccountTeamMembership saveMembership(AccountTeamMembership membership);

    Optional<AccountTeamMembership> findMembership(UUID accountId, UUID teamId);

    Optional<AccountTeamMembership> findMembershipByMemberId(UUID memberId);

    RoundRoomTombstone saveTombstone(RoundRoomTombstone tombstone);

    Optional<RoundRoomTombstone> findTombstoneForUpdate(String roomId);

    RoomMappingCreationResult createMapping(
            RoundRoomTombstone tombstone,
            RoundRoomMapping mapping
    );

    Optional<RoundRoomMapping> findMappingByRoomId(String roomId);

    Optional<RoundRoomMapping> findMappingByResourceId(UUID resourceId);

    void deleteMapping(RoundRoomMapping mapping);
}
