package com.personal.baton.adapter.out.persistence.roundauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.personal.baton.application.roundauth.port.out.RoundAuthorizationRepository.MembershipClaimResult;
import com.personal.baton.adapter.out.persistence.roundauth.AccountTeamMembershipClaimTransaction.MembershipInsertException;
import com.personal.baton.application.roundauth.port.out.RoundAuthorizationRepository.RoomMappingCreationResult;
import com.personal.baton.adapter.out.persistence.roundauth.RoundRoomMappingCreationTransaction.MappingInsertException;
import com.personal.baton.adapter.out.persistence.roundauth.RoundRoomMappingCreationTransaction.TombstoneInsertException;
import com.personal.baton.domain.roundauth.AccountTeamMembership;
import com.personal.baton.domain.roundauth.RoundRoomMapping;
import com.personal.baton.domain.roundauth.RoundRoomTombstone;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class RoundAuthorizationPersistenceAdapterTest {

    private static final UUID ACCOUNT_ID = UUID.randomUUID();
    private static final UUID TEAM_ID = UUID.randomUUID();
    private static final UUID SEASON_ID = UUID.randomUUID();
    private static final UUID MEMBER_ID = UUID.randomUUID();
    private static final UUID RESOURCE_ID = UUID.randomUUID();
    private static final String ROOM_ID = "abcd-efgh-jkmn";
    private static final Instant NOW = Instant.parse("2026-08-08T11:00:00Z");

    @Mock
    private AccountTeamMembershipJpaRepository membershipRepository;

    @Mock
    private RoundRoomTombstoneJpaRepository tombstoneRepository;

    @Mock
    private RoundRoomMappingJpaRepository mappingRepository;

    @Mock
    private AccountTeamMembershipClaimTransaction membershipClaimTransaction;

    @Mock
    private RoundRoomMappingCreationTransaction mappingCreationTransaction;

    private RoundAuthorizationPersistenceAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new RoundAuthorizationPersistenceAdapter(
                membershipRepository,
                tombstoneRepository,
                mappingRepository,
                membershipClaimTransaction,
                mappingCreationTransaction
        );
    }

    @Test
    @DisplayName("계정과 팀, 구성원 및 방 매핑 조회를 Spring Data repository에 위임한다")
    void delegateRoundAuthorizationQueries() {
        AccountTeamMembership membership = membership();
        RoundRoomMapping mapping = mapping();
        RoundRoomTombstone tombstone = tombstone();
        when(membershipRepository.findByAccountIdAndTeamId(ACCOUNT_ID, TEAM_ID))
                .thenReturn(Optional.of(membership));
        when(membershipRepository.findByMemberId(MEMBER_ID))
                .thenReturn(Optional.of(membership));
        when(mappingRepository.findByRoomId(ROOM_ID)).thenReturn(Optional.of(mapping));
        when(mappingRepository.findByResourceId(RESOURCE_ID)).thenReturn(Optional.of(mapping));
        when(tombstoneRepository.findByRoomIdForUpdate(ROOM_ID))
                .thenReturn(Optional.of(tombstone));

        assertThat(adapter.findMembership(ACCOUNT_ID, TEAM_ID)).contains(membership);
        assertThat(adapter.findMembershipByMemberId(MEMBER_ID)).contains(membership);
        assertThat(adapter.findMappingByRoomId(ROOM_ID)).contains(mapping);
        assertThat(adapter.findMappingByResourceId(RESOURCE_ID)).contains(mapping);
        assertThat(adapter.findTombstoneForUpdate(ROOM_ID)).contains(tombstone);
    }

    @Test
    @DisplayName("구성원 멤버십 unique 경쟁은 실패 transaction 밖에서 승자 결과로 변환한다")
    void convergeMembershipUniqueConflictOnWinner() {
        AccountTeamMembership membership = membership();
        AccountTeamMembership winner = membership();
        DataIntegrityViolationException cause = uniqueViolation(
                "baton.uk_account_team_memberships_member"
        );
        when(membershipClaimTransaction.create(membership))
                .thenThrow(new MembershipInsertException(cause));
        when(membershipClaimTransaction.findByMemberId(MEMBER_ID))
                .thenReturn(Optional.of(winner));

        assertThat(adapter.claimMembership(membership))
                .isEqualTo(new MembershipClaimResult.AlreadyClaimed(winner));
    }

    @Test
    @DisplayName("방 식별자의 원자적 insert 경쟁은 다음 생성을 위한 명시적 결과로 반환한다")
    void returnRetryableResultForRoomIdConflict() {
        RoundRoomMapping mapping = mapping();
        RoundRoomTombstone tombstone = tombstone();
        DataIntegrityViolationException cause = uniqueViolation(
                "PRIMARY"
        );
        when(mappingCreationTransaction.create(tombstone, mapping))
                .thenThrow(new TombstoneInsertException(cause));

        assertThat(adapter.createMapping(tombstone, mapping))
                .isInstanceOf(RoomMappingCreationResult.RoomIdUnavailable.class);
    }

    @Test
    @DisplayName("같은 resource의 원자적 insert 경쟁은 승자가 만든 매핑으로 수렴한다")
    void returnExistingMappingForResourceConflict() {
        RoundRoomMapping mapping = mapping();
        RoundRoomMapping existing = RoundRoomMapping.create(
                UUID.randomUUID(),
                "bcdf-ghjk-mnpq",
                TEAM_ID,
                SEASON_ID,
                RESOURCE_ID,
                NOW.minusSeconds(1)
        );
        RoundRoomTombstone tombstone = tombstone();
        DataIntegrityViolationException cause = uniqueViolation(
                "uk_round_room_mappings_resource"
        );
        when(mappingCreationTransaction.create(tombstone, mapping))
                .thenThrow(new MappingInsertException(cause));
        when(mappingCreationTransaction.findByResourceId(RESOURCE_ID))
                .thenReturn(Optional.of(existing));

        assertThat(adapter.createMapping(tombstone, mapping))
                .isEqualTo(new RoomMappingCreationResult.ResourceAlreadyMapped(existing));
    }

    @Test
    @DisplayName("resource 경쟁 직후 방이 종료되면 새 식별자로 다시 시도할 수 있다")
    void retryWhenConflictingResourceMappingWasAlreadyEnded() {
        RoundRoomMapping mapping = mapping();
        RoundRoomTombstone tombstone = tombstone();
        DataIntegrityViolationException cause = uniqueViolation(
                "uk_round_room_mappings_resource"
        );
        when(mappingCreationTransaction.create(tombstone, mapping))
                .thenThrow(new MappingInsertException(cause));
        when(mappingCreationTransaction.findByResourceId(RESOURCE_ID))
                .thenReturn(Optional.empty());

        assertThat(adapter.createMapping(tombstone, mapping))
                .isInstanceOf(RoomMappingCreationResult.ResourceBecameAvailable.class);
    }

    @Test
    @DisplayName("식별하지 않은 데이터 제약 위반은 원래 예외를 유지한다")
    void preserveUnknownConstraintViolation() {
        RoundRoomMapping mapping = mapping();
        RoundRoomTombstone tombstone = tombstone();
        DataIntegrityViolationException cause = uniqueViolation("uk_unknown_round_constraint");
        when(mappingCreationTransaction.create(tombstone, mapping))
                .thenThrow(new MappingInsertException(cause));

        assertThatThrownBy(() -> adapter.createMapping(tombstone, mapping)).isSameAs(cause);
    }

    @Test
    @DisplayName("방 매핑 종료는 삭제 제약을 트랜잭션 안에서 즉시 확인한다")
    void flushDeletedMapping() {
        RoundRoomMapping mapping = mapping();

        adapter.deleteMapping(mapping);

        verify(mappingRepository).delete(mapping);
        verify(mappingRepository).flush();
    }

    private AccountTeamMembership membership() {
        return AccountTeamMembership.create(
                UUID.randomUUID(),
                ACCOUNT_ID,
                TEAM_ID,
                MEMBER_ID,
                NOW
        );
    }

    private RoundRoomMapping mapping() {
        return RoundRoomMapping.create(
                UUID.randomUUID(),
                ROOM_ID,
                TEAM_ID,
                SEASON_ID,
                RESOURCE_ID,
                NOW
        );
    }

    private RoundRoomTombstone tombstone() {
        return RoundRoomTombstone.create(
                ROOM_ID,
                TEAM_ID,
                SEASON_ID,
                RESOURCE_ID,
                NOW
        );
    }

    private DataIntegrityViolationException uniqueViolation(String constraintName) {
        ConstraintViolationException violation = mock(ConstraintViolationException.class);
        when(violation.getConstraintName()).thenReturn(constraintName);
        return new DataIntegrityViolationException("고유 제약 충돌", violation);
    }
}
