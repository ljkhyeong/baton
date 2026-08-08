package com.personal.baton.application.roundauth.port.out;

import com.personal.baton.domain.roundauth.AccountTeamMembership;
import com.personal.baton.domain.roundauth.RoundRoomMapping;
import com.personal.baton.domain.roundauth.RoundRoomTombstone;
import java.util.Optional;
import java.util.UUID;

public interface RoundAuthorizationRepository {

    AccountTeamMembership saveMembership(AccountTeamMembership membership);

    Optional<AccountTeamMembership> findMembership(UUID accountId, UUID teamId);

    Optional<AccountTeamMembership> findMembershipByMemberId(UUID memberId);

    RoundRoomTombstone saveTombstone(RoundRoomTombstone tombstone);

    Optional<RoundRoomTombstone> findTombstone(String roomId);

    Optional<RoundRoomTombstone> findTombstoneForUpdate(String roomId);

    RoundRoomMapping saveMapping(RoundRoomMapping mapping);

    Optional<RoundRoomMapping> findMappingByRoomId(String roomId);

    Optional<RoundRoomMapping> findMappingByResourceId(UUID resourceId);

    void deleteMapping(RoundRoomMapping mapping);
}
