package com.personal.baton.application.roundauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.personal.baton.BatonApplication;
import com.personal.baton.adapter.out.persistence.roundauth.RoundAuthorizationPersistenceAdapter;
import com.personal.baton.application.roundauth.error.AccountMembershipConflictException;
import com.personal.baton.application.roundauth.error.RoundParticipationDeniedException;
import com.personal.baton.application.roundauth.error.RoundRoomNotFoundException;
import com.personal.baton.application.roundauth.port.in.RoundAuthorizationUseCase;
import com.personal.baton.application.roundauth.port.in.RoundAuthorizationUseCase.ClaimMembershipCommand;
import com.personal.baton.application.roundauth.port.in.RoundAuthorizationUseCase.CreateRoomMappingCommand;
import com.personal.baton.application.roundauth.port.in.RoundAuthorizationUseCase.CurrentRoomMappingsQuery;
import com.personal.baton.application.roundauth.port.in.RoundAuthorizationUseCase.EndRoomMappingCommand;
import com.personal.baton.application.roundauth.port.in.RoundAuthorizationUseCase.IssueParticipationGrantCommand;
import com.personal.baton.application.roundauth.port.in.RoundAuthorizationUseCase.ParticipationGrantResult;
import com.personal.baton.application.roundauth.port.in.RoundAuthorizationUseCase.RoomMappingResult;
import com.personal.baton.application.roundauth.port.out.ParticipationGrantSigner;
import com.personal.baton.application.roundauth.port.out.ParticipationGrantSigner.ParticipationGrantClaims;
import com.personal.baton.application.roundauth.port.out.RoundAuthorizationRepository;
import com.personal.baton.application.roundauth.port.out.RoundAuthorizationRepository.MembershipClaimResult;
import com.personal.baton.application.roundauth.port.out.RoundRoomIdGenerator;
import com.personal.baton.application.workspace.error.WorkspaceAccessDeniedException;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.CreateRoleCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.CreateRoleResourceCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.CreateWorkspaceCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.CreatedWorkspaceResult;
import com.personal.baton.domain.roundauth.AccountTeamMembership;
import com.personal.baton.domain.roundauth.RoundRoomMapping;
import com.personal.baton.domain.roundauth.RoundRoomTombstone;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Tag("usecase")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        classes = {
            BatonApplication.class,
            RoundAuthorizationPersistenceUseCaseTest.RoundAuthorizationTestConfiguration.class
        },
        properties = {
            "baton.workspace.creation-key=pilot-operator-key-0000000000000001",
            "baton.workspace.recovery-key=pilot-recovery-key-0000000000000002",
            "baton.round-automation.poll-interval=PT24H",
            "baton.identity.email-verification.outbox-encryption-key="
                    + "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=",
            "baton.identity.email-verification.dispatch-interval=PT24H"
        }
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RoundAuthorizationPersistenceUseCaseTest {

    private static final String CREATION_KEY = "pilot-operator-key-0000000000000001";
    private static final Instant CREATED_AT = Instant.parse("2026-08-08T10:00:00Z");
    private static final Instant ENDED_AT = Instant.parse("2026-08-08T10:05:00Z");

    @Container
    @ServiceConnection
    private static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:b3b90af2a6552ae30c266fdb7d5dd55f3afb72404bb78d37fe8a23eb857fd3fb"
    )
            .withDatabaseName("baton_round_authorization_usecase")
            .withUsername("baton")
            .withPassword("password");

    @Autowired
    private RoundAuthorizationUseCase roundAuthorizationUseCase;

    @Autowired
    private WorkspaceUseCase workspaceUseCase;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MutableClock mutableClock;

    @Autowired
    private FailingRoundAuthorizationRepository roundRepository;

    @Autowired
    private ControllableRoundRoomIdGenerator roomIdGenerator;

    @Autowired
    private ControllableParticipationGrantSigner grantSigner;

    @BeforeEach
    void setUp() {
        mutableClock.setInstant(CREATED_AT);
        roundRepository.resetFailures();
        roomIdGenerator.reset();
        grantSigner.reset();
    }

    @Test
    @DisplayName("실제 tombstone PK 경쟁은 실패한 트랜잭션을 끝낸 뒤 새 room ID로 재시도한다")
    void retriesAfterActualTombstonePrimaryKeyConflict() {
        RoundFixture fixture = createFixture();
        String usedRoomId = "aaaa-aaaa-aaaa";
        String freshRoomId = "bbbb-bbbb-bbbb";
        jdbcTemplate.update(
                """
                INSERT INTO round_room_tombstones (
                    room_id,
                    created_at,
                    team_id,
                    season_id,
                    resource_id,
                    ended_at
                ) VALUES (?, ?, UUID_TO_BIN(?), UUID_TO_BIN(?), UUID_TO_BIN(?), NULL)
                """,
                usedRoomId,
                LocalDateTime.ofInstant(CREATED_AT.minusSeconds(60), ZoneOffset.UTC),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString()
        );
        roomIdGenerator.enqueue(usedRoomId, freshRoomId);

        RoomMappingResult created = createRoomMapping(fixture);

        assertThat(created.roomId()).isEqualTo(freshRoomId);
        assertThat(created.teamId()).isEqualTo(fixture.workspace().teamId());
        assertThat(created.seasonId()).isEqualTo(fixture.workspace().seasonId());
        assertThat(created.resourceId()).isEqualTo(fixture.resourceId());
        assertThat(storedTombstoneCount(fixture.resourceId())).isOne();
        assertThat(storedMappingCount(fixture.resourceId())).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM round_room_mappings WHERE room_id = ?",
                Integer.class,
                usedRoomId
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM round_room_mappings mapping
                JOIN round_room_tombstones tombstone
                  ON tombstone.room_id = mapping.room_id
                 AND tombstone.team_id = mapping.team_id
                 AND tombstone.season_id = mapping.season_id
                 AND tombstone.resource_id = mapping.resource_id
                WHERE mapping.room_id = ?
                """,
                Integer.class,
                created.roomId()
        )).isOne();
    }

    @Test
    @DisplayName("같은 resource를 동시에 생성한 두 요청은 DB unique 경쟁 뒤 하나의 매핑으로 수렴한다")
    void convergesConcurrentResourceCreationOnOneMapping() throws Exception {
        RoundFixture fixture = createFixture();
        roomIdGenerator.synchronizeNextPair(
                "cccc-cccc-cccc",
                "dddd-dddd-dddd"
        );
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<RoomMappingResult> first = executor.submit(
                    () -> createRoomMapping(fixture)
            );
            Future<RoomMappingResult> second = executor.submit(
                    () -> createRoomMapping(fixture)
            );

            RoomMappingResult firstResult = first.get(20, TimeUnit.SECONDS);
            RoomMappingResult secondResult = second.get(20, TimeUnit.SECONDS);

            assertThat(secondResult).isEqualTo(firstResult);
            assertThat(firstResult.teamId()).isEqualTo(fixture.workspace().teamId());
            assertThat(firstResult.seasonId()).isEqualTo(fixture.workspace().seasonId());
            assertThat(firstResult.resourceId()).isEqualTo(fixture.resourceId());
            assertThat(storedTombstoneCount(fixture.resourceId())).isOne();
            assertThat(storedMappingCount(fixture.resourceId())).isOne();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("실제 MySQL batch 조회는 team과 season의 active 매핑만 resource UUID 순서로 반환한다")
    void queriesActiveMappingsByTeamAndSeasonInResourceOrder() {
        RoundFixture fixture = createFixture();
        UUID secondResourceId = createAdditionalResource(fixture);
        RoundFixture otherScope = createFixture();
        RoomMappingResult first = createRoomMapping(fixture);
        RoomMappingResult second = createRoomMapping(fixture, secondResourceId);
        createRoomMapping(otherScope);

        List<UUID> expectedResourceOrder = List.of(
                        fixture.resourceId(),
                        secondResourceId
                ).stream()
                .sorted(Comparator.comparing(UUID::toString))
                .toList();
        List<RoomMappingResult> mappings = roundAuthorizationUseCase
                .findCurrentRoomMappings(new CurrentRoomMappingsQuery(
                        fixture.accountId(),
                        fixture.workspace().teamId(),
                        fixture.workspace().seasonId(),
                        fixture.workspace().accessKey()
                ));

        assertThat(mappings)
                .extracting(RoomMappingResult::resourceId)
                .containsExactlyElementsOf(expectedResourceOrder);
        assertThat(mappings).allSatisfy(mapping -> {
            assertThat(mapping.teamId()).isEqualTo(fixture.workspace().teamId());
            assertThat(mapping.seasonId()).isEqualTo(fixture.workspace().seasonId());
            assertThat(mapping.endedAt()).isNull();
        });

        mutableClock.setInstant(ENDED_AT);
        endRoomMapping(fixture, first.roomId());

        assertThat(roundAuthorizationUseCase.findCurrentRoomMappings(
                new CurrentRoomMappingsQuery(
                        fixture.accountId(),
                        fixture.workspace().teamId(),
                        fixture.workspace().seasonId(),
                        fixture.workspace().accessKey()
                )
        )).containsExactly(second);
    }

    @Test
    @DisplayName("같은 계정과 구성원의 동시 membership claim은 DB unique 경쟁 뒤 한 결과로 수렴한다")
    void convergesConcurrentMembershipClaim() throws Exception {
        RoundFixture fixture = createUnclaimedFixture();
        AccountTeamMembership firstCandidate = AccountTeamMembership.create(
                UUID.randomUUID(),
                fixture.accountId(),
                fixture.workspace().teamId(),
                fixture.memberId(),
                CREATED_AT
        );
        AccountTeamMembership secondCandidate = AccountTeamMembership.create(
                UUID.randomUUID(),
                fixture.accountId(),
                fixture.workspace().teamId(),
                fixture.memberId(),
                CREATED_AT
        );
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<MembershipClaimResult> first = executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return roundRepository.claimMembership(firstCandidate);
            });
            Future<MembershipClaimResult> second = executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return roundRepository.claimMembership(secondCandidate);
            });

            MembershipClaimResult firstResult = first.get(20, TimeUnit.SECONDS);
            MembershipClaimResult secondResult = second.get(20, TimeUnit.SECONDS);
            AccountTeamMembership firstMembership = claimedMembership(firstResult);
            AccountTeamMembership secondMembership = claimedMembership(secondResult);

            assertThat(firstMembership.getId()).isEqualTo(secondMembership.getId());
            assertThat(List.of(firstResult, secondResult))
                    .anyMatch(MembershipClaimResult.Claimed.class::isInstance)
                    .anyMatch(MembershipClaimResult.AlreadyClaimed.class::isInstance);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM account_team_memberships "
                            + "WHERE account_id = UUID_TO_BIN(?) AND team_id = UUID_TO_BIN(?)",
                    Integer.class,
                    fixture.accountId().toString(),
                    fixture.workspace().teamId().toString()
            )).isOne();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("다른 계정이 점유한 구성원 연결은 DB unique 판정 뒤 구성원 충돌로 수렴한다")
    void rejectsMembershipClaimOwnedByAnotherAccount() {
        RoundFixture fixture = createUnclaimedFixture();
        UUID otherAccountId = UUID.randomUUID();
        LocalDateTime accountCreatedAt = LocalDateTime.ofInstant(CREATED_AT, ZoneOffset.UTC);
        jdbcTemplate.update(
                "INSERT INTO accounts (id, display_name, created_at, updated_at) "
                        + "VALUES (UUID_TO_BIN(?), ?, ?, ?)",
                otherAccountId.toString(),
                "ROUND 다른 테스트 계정",
                accountCreatedAt,
                accountCreatedAt
        );
        roundAuthorizationUseCase.claimMembership(new ClaimMembershipCommand(
                fixture.accountId(),
                fixture.workspace().teamId(),
                fixture.workspace().seasonId(),
                fixture.memberId(),
                fixture.workspace().accessKey()
        ));

        assertThatThrownBy(() -> roundAuthorizationUseCase.claimMembership(
                new ClaimMembershipCommand(
                        otherAccountId,
                        fixture.workspace().teamId(),
                        fixture.workspace().seasonId(),
                        fixture.memberId(),
                        fixture.workspace().accessKey()
                )
        ))
                .isInstanceOf(AccountMembershipConflictException.class)
                .hasMessage("이 구성원은 다른 계정과 이미 연결되어 있습니다");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM account_team_memberships WHERE member_id = UUID_TO_BIN(?)",
                Integer.class,
                fixture.memberId().toString()
        )).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT BIN_TO_UUID(account_id) FROM account_team_memberships "
                        + "WHERE member_id = UUID_TO_BIN(?)",
                String.class,
                fixture.memberId().toString()
        )).isEqualTo(fixture.accountId().toString());
    }

    @Test
    @DisplayName("방 종료 실패는 tombstone과 매핑을 함께 복원하고 응답 손실 재시도는 권한 확인 뒤 최초 결과를 재생한다")
    void atomicallyEndsAndReplaysRoomMapping() {
        RoundFixture fixture = createFixture();
        RoomMappingResult created = createRoomMapping(fixture);
        mutableClock.setInstant(ENDED_AT);
        roundRepository.failNextMappingDelete();

        assertThatThrownBy(() -> endRoomMapping(fixture, created.roomId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("매핑 삭제 실패");

        assertThat(storedMappingCount(fixture.resourceId())).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT ended_at IS NULL FROM round_room_tombstones WHERE room_id = ?",
                Boolean.class,
                created.roomId()
        )).isTrue();

        RoomMappingResult firstResult = endRoomMapping(fixture, created.roomId());
        assertThat(firstResult.endedAt()).isEqualTo(ENDED_AT);
        assertThat(firstResult.teamId()).isEqualTo(fixture.workspace().teamId());
        assertThat(firstResult.seasonId()).isEqualTo(fixture.workspace().seasonId());
        assertThat(firstResult.resourceId()).isEqualTo(fixture.resourceId());
        assertThat(storedMappingCount(fixture.resourceId())).isZero();

        mutableClock.setInstant(ENDED_AT.plusSeconds(60));
        assertThatThrownBy(() -> roundAuthorizationUseCase.endRoomMapping(
                new EndRoomMappingCommand(
                        fixture.accountId(),
                        created.roomId(),
                        "wrong-access-key"
                )
        )).isInstanceOf(WorkspaceAccessDeniedException.class);
        assertThatThrownBy(() -> roundAuthorizationUseCase.endRoomMapping(
                new EndRoomMappingCommand(
                        UUID.randomUUID(),
                        created.roomId(),
                        fixture.workspace().accessKey()
                )
        )).isInstanceOf(RoundParticipationDeniedException.class);

        RoomMappingResult replayed = endRoomMapping(fixture, created.roomId());

        assertThat(replayed).isEqualTo(firstResult);
        assertThat(replayed.createdAt()).isEqualTo(created.createdAt());
        assertThat(replayed.endedAt()).isEqualTo(ENDED_AT);
        assertThat(storedTombstoneCount(fixture.resourceId())).isOne();
    }

    @Test
    @DisplayName("참여권 발급의 공유 잠금이 유지되는 동안 방 종료의 배타 잠금은 대기한다")
    void endWaitsForParticipationGrantSharedLock() throws Exception {
        RoundFixture fixture = createFixture();
        RoomMappingResult created = createRoomMapping(fixture);
        SignProbe signProbe = grantSigner.blockNextSign();
        LockProbe updateProbe = roundRepository.observeNextUpdateLock();
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<ParticipationGrantResult> grant = executor.submit(() ->
                    issueParticipationGrant(fixture, created.roomId())
            );
            assertThat(signProbe.entered().await(10, TimeUnit.SECONDS)).isTrue();

            mutableClock.setInstant(ENDED_AT);
            Future<RoomMappingResult> end = executor.submit(() ->
                    endRoomMapping(fixture, created.roomId())
            );
            assertThat(updateProbe.requested().await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(updateProbe.acquired().await(500, TimeUnit.MILLISECONDS)).isFalse();
            assertThat(end.isDone()).isFalse();

            signProbe.release().countDown();

            assertThat(grant.get(10, TimeUnit.SECONDS).token()).isEqualTo("test-participation-grant");
            assertThat(end.get(10, TimeUnit.SECONDS).endedAt()).isEqualTo(ENDED_AT);
            assertThat(updateProbe.acquired().getCount()).isZero();
            assertThat(storedMappingCount(fixture.resourceId())).isZero();
        } finally {
            signProbe.release().countDown();
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("방 종료가 먼저 배타 잠금을 얻으면 대기하던 참여권 발급은 종료 상태를 보고 거부된다")
    void grantWaitingBehindRoomEndIsRejected() throws Exception {
        RoundFixture fixture = createFixture();
        RoomMappingResult created = createRoomMapping(fixture);
        LockProbe updateProbe = roundRepository.blockNextUpdateLockAfterAcquire();
        LockProbe shareProbe = roundRepository.observeNextShareLock();
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            mutableClock.setInstant(ENDED_AT);
            Future<RoomMappingResult> end = executor.submit(() ->
                    endRoomMapping(fixture, created.roomId())
            );
            assertThat(updateProbe.acquired().await(10, TimeUnit.SECONDS)).isTrue();

            Future<ParticipationGrantResult> grant = executor.submit(() ->
                    issueParticipationGrant(fixture, created.roomId())
            );
            assertThat(shareProbe.requested().await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(shareProbe.acquired().await(500, TimeUnit.MILLISECONDS)).isFalse();
            assertThat(grant.isDone()).isFalse();

            updateProbe.release().countDown();

            assertThat(end.get(10, TimeUnit.SECONDS).endedAt()).isEqualTo(ENDED_AT);
            assertThatThrownBy(() -> grant.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(RoundRoomNotFoundException.class);
            assertThat(shareProbe.acquired().getCount()).isZero();
            assertThat(storedMappingCount(fixture.resourceId())).isZero();
            assertThat(grantSigner.signCount()).isZero();
        } finally {
            updateProbe.release().countDown();
            executor.shutdownNow();
        }
    }

    private RoundFixture createFixture() {
        RoundFixture fixture = createUnclaimedFixture();
        roundAuthorizationUseCase.claimMembership(new ClaimMembershipCommand(
                fixture.accountId(),
                fixture.workspace().teamId(),
                fixture.workspace().seasonId(),
                fixture.memberId(),
                fixture.workspace().accessKey()
        ));
        return fixture;
    }

    private RoundFixture createUnclaimedFixture() {
        String suffix = UUID.randomUUID().toString();
        CreatedWorkspaceResult workspace = workspaceUseCase.createWorkspace(
                "round-auth-workspace-" + suffix,
                CREATION_KEY,
                new CreateWorkspaceCommand(
                        "ROUND 원자성 검증 팀",
                        "ROUND 원자성 검증 시즌",
                        LocalDate.of(2026, 8, 1),
                        LocalDate.of(2026, 12, 31),
                        List.of("ROUND 사용자")
                )
        );
        UUID memberId = workspaceUseCase.getWorkspace(
                workspace.teamId(),
                workspace.seasonId(),
                workspace.accessKey()
        ).members().getFirst().id();
        var role = workspaceUseCase.createRole(
                workspace.teamId(),
                workspace.seasonId(),
                "round-auth-role-" + suffix,
                workspace.accessKey(),
                new CreateRoleCommand(
                        "ROUND 진행자",
                        "ROUND 방을 운영합니다",
                        memberId,
                        null,
                        LocalDate.of(2026, 8, 1),
                        null,
                        List.of("ROUND 자료 관리"),
                        null
                )
        );
        UUID resourceId = workspaceUseCase.createRoleResource(
                workspace.teamId(),
                workspace.seasonId(),
                "round-auth-resource-" + suffix,
                workspace.accessKey(),
                new CreateRoleResourceCommand(
                        role.id(),
                        "ROUND 스터디룸",
                        "https://round.example.com/" + suffix,
                        "ROUND authorization transaction 검증 자료"
                )
        ).id();
        UUID accountId = UUID.randomUUID();
        LocalDateTime accountCreatedAt = LocalDateTime.ofInstant(CREATED_AT, ZoneOffset.UTC);
        jdbcTemplate.update(
                "INSERT INTO accounts (id, display_name, created_at, updated_at) "
                        + "VALUES (UUID_TO_BIN(?), ?, ?, ?)",
                accountId.toString(),
                "ROUND 테스트 계정",
                accountCreatedAt,
                accountCreatedAt
        );
        return new RoundFixture(workspace, accountId, memberId, role.id(), resourceId);
    }

    private UUID createAdditionalResource(RoundFixture fixture) {
        String suffix = UUID.randomUUID().toString();
        return workspaceUseCase.createRoleResource(
                fixture.workspace().teamId(),
                fixture.workspace().seasonId(),
                "round-auth-additional-resource-" + suffix,
                fixture.workspace().accessKey(),
                new CreateRoleResourceCommand(
                        fixture.roleId(),
                        "ROUND 회고 자료",
                        "https://round.example.com/retrospective/" + suffix,
                        "ROUND batch mapping 조회 검증 자료"
                )
        ).id();
    }

    private AccountTeamMembership claimedMembership(MembershipClaimResult result) {
        return switch (result) {
            case MembershipClaimResult.Claimed claimed -> claimed.membership();
            case MembershipClaimResult.AlreadyClaimed existing -> existing.membership();
        };
    }

    private RoomMappingResult createRoomMapping(RoundFixture fixture) {
        return createRoomMapping(fixture, fixture.resourceId());
    }

    private RoomMappingResult createRoomMapping(RoundFixture fixture, UUID resourceId) {
        return roundAuthorizationUseCase.createRoomMapping(new CreateRoomMappingCommand(
                fixture.accountId(),
                fixture.workspace().teamId(),
                fixture.workspace().seasonId(),
                resourceId,
                fixture.workspace().accessKey()
        ));
    }

    private RoomMappingResult endRoomMapping(RoundFixture fixture, String roomId) {
        return roundAuthorizationUseCase.endRoomMapping(new EndRoomMappingCommand(
                fixture.accountId(),
                roomId,
                fixture.workspace().accessKey()
        ));
    }

    private ParticipationGrantResult issueParticipationGrant(
            RoundFixture fixture,
            String roomId
    ) {
        return roundAuthorizationUseCase.issueParticipationGrant(
                new IssueParticipationGrantCommand(fixture.accountId(), roomId, null)
        );
    }

    private int storedTombstoneCount(UUID resourceId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM round_room_tombstones WHERE resource_id = UUID_TO_BIN(?)",
                Integer.class,
                resourceId.toString()
        );
    }

    private int storedMappingCount(UUID resourceId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM round_room_mappings WHERE resource_id = UUID_TO_BIN(?)",
                Integer.class,
                resourceId.toString()
        );
    }

    private record RoundFixture(
            CreatedWorkspaceResult workspace,
            UUID accountId,
            UUID memberId,
            UUID roleId,
            UUID resourceId
    ) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RoundAuthorizationTestConfiguration {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(CREATED_AT);
        }

        @Bean
        @Primary
        FailingRoundAuthorizationRepository failingRoundAuthorizationRepository(
                RoundAuthorizationPersistenceAdapter delegate
        ) {
            return new FailingRoundAuthorizationRepository(delegate);
        }

        @Bean
        @Primary
        ControllableRoundRoomIdGenerator controllableRoundRoomIdGenerator() {
            return new ControllableRoundRoomIdGenerator();
        }

        @Bean
        @Primary
        ControllableParticipationGrantSigner controllableParticipationGrantSigner() {
            return new ControllableParticipationGrantSigner();
        }
    }

    static final class ControllableRoundRoomIdGenerator implements RoundRoomIdGenerator {

        private static final char[] ALPHABET =
                "abcdefghjkmnpqrstuvwxyz23456789".toCharArray();

        private final ConcurrentLinkedQueue<String> queuedIds =
                new ConcurrentLinkedQueue<>();
        private final AtomicLong sequence = new AtomicLong();
        private final AtomicReference<CyclicBarrier> pairBarrier = new AtomicReference<>();
        private final AtomicReference<List<String>> pairIds = new AtomicReference<>();
        private final AtomicInteger pairIndex = new AtomicInteger();

        void enqueue(String... roomIds) {
            queuedIds.addAll(List.of(roomIds));
        }

        void synchronizeNextPair(String firstRoomId, String secondRoomId) {
            pairIds.set(List.of(firstRoomId, secondRoomId));
            pairIndex.set(0);
            pairBarrier.set(new CyclicBarrier(2));
        }

        void reset() {
            queuedIds.clear();
            pairIds.set(null);
            pairBarrier.set(null);
            pairIndex.set(0);
        }

        @Override
        public String generate() {
            CyclicBarrier barrier = pairBarrier.get();
            if (barrier != null) {
                int index = pairIndex.getAndIncrement();
                try {
                    barrier.await(10, TimeUnit.SECONDS);
                } catch (Exception exception) {
                    throw new IllegalStateException(
                            "동시 room ID 생성 준비에 실패했습니다",
                            exception
                    );
                }
                return pairIds.get().get(index);
            }

            String queued = queuedIds.poll();
            if (queued != null) {
                return queued;
            }
            return nextUniqueRoomId();
        }

        private String nextUniqueRoomId() {
            long value = sequence.incrementAndGet();
            char[] characters = new char[12];
            for (int index = characters.length - 1; index >= 0; index--) {
                characters[index] = ALPHABET[(int) (value % ALPHABET.length)];
                value /= ALPHABET.length;
            }
            return new String(characters, 0, 4)
                    + "-"
                    + new String(characters, 4, 4)
                    + "-"
                    + new String(characters, 8, 4);
        }
    }

    static final class MutableClock extends Clock {

        private final AtomicReference<Instant> currentInstant;

        private MutableClock(Instant initialInstant) {
            this.currentInstant = new AtomicReference<>(initialInstant);
        }

        void setInstant(Instant instant) {
            currentInstant.set(instant);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return currentInstant.get();
        }
    }

    static final class FailingRoundAuthorizationRepository
            implements RoundAuthorizationRepository {

        private final RoundAuthorizationRepository delegate;
        private final AtomicBoolean failMappingDelete = new AtomicBoolean();
        private final AtomicReference<LockProbe> nextUpdateLock = new AtomicReference<>();
        private final AtomicReference<LockProbe> nextShareLock = new AtomicReference<>();

        private FailingRoundAuthorizationRepository(RoundAuthorizationRepository delegate) {
            this.delegate = delegate;
        }

        void failNextMappingDelete() {
            failMappingDelete.set(true);
        }

        void resetFailures() {
            failMappingDelete.set(false);
            release(nextUpdateLock.getAndSet(null));
            release(nextShareLock.getAndSet(null));
        }

        LockProbe observeNextUpdateLock() {
            return installProbe(nextUpdateLock, false);
        }

        LockProbe blockNextUpdateLockAfterAcquire() {
            return installProbe(nextUpdateLock, true);
        }

        LockProbe observeNextShareLock() {
            return installProbe(nextShareLock, false);
        }

        @Override
        public MembershipClaimResult claimMembership(AccountTeamMembership membership) {
            return delegate.claimMembership(membership);
        }

        @Override
        public Optional<AccountTeamMembership> findMembership(UUID accountId, UUID teamId) {
            return delegate.findMembership(accountId, teamId);
        }

        @Override
        public RoundRoomTombstone saveTombstone(RoundRoomTombstone tombstone) {
            return delegate.saveTombstone(tombstone);
        }

        @Override
        public Optional<RoundRoomTombstone> findTombstoneForUpdate(String roomId) {
            return withProbe(nextUpdateLock.getAndSet(null), () ->
                    delegate.findTombstoneForUpdate(roomId)
            );
        }

        @Override
        public Optional<RoundRoomTombstone> findTombstoneForShare(String roomId) {
            return withProbe(nextShareLock.getAndSet(null), () ->
                    delegate.findTombstoneForShare(roomId)
            );
        }

        @Override
        public RoomMappingCreationResult createMapping(
                RoundRoomTombstone tombstone,
                RoundRoomMapping mapping
        ) {
            return delegate.createMapping(tombstone, mapping);
        }

        @Override
        public Optional<RoundRoomMapping> findMappingByRoomId(String roomId) {
            return delegate.findMappingByRoomId(roomId);
        }

        @Override
        public Optional<RoundRoomMapping> findMappingByResourceId(UUID resourceId) {
            return delegate.findMappingByResourceId(resourceId);
        }

        @Override
        public List<RoundRoomMapping> findMappingsByTeamIdAndSeasonId(
                UUID teamId,
                UUID seasonId
        ) {
            return delegate.findMappingsByTeamIdAndSeasonId(teamId, seasonId);
        }

        @Override
        public void deleteMapping(RoundRoomMapping mapping) {
            if (failMappingDelete.compareAndSet(true, false)) {
                throw new IllegalStateException("의도한 ROUND 매핑 삭제 실패");
            }
            delegate.deleteMapping(mapping);
        }

        private LockProbe installProbe(
                AtomicReference<LockProbe> target,
                boolean blockAfterAcquire
        ) {
            LockProbe probe = new LockProbe(
                    new CountDownLatch(1),
                    new CountDownLatch(1),
                    new CountDownLatch(blockAfterAcquire ? 1 : 0)
            );
            if (!target.compareAndSet(null, probe)) {
                throw new IllegalStateException("이미 다음 ROUND 잠금 관찰이 설정되어 있습니다");
            }
            return probe;
        }

        private <T> T withProbe(
                LockProbe probe,
                Supplier<T> operation
        ) {
            if (probe == null) {
                return operation.get();
            }
            probe.requested().countDown();
            T result = operation.get();
            probe.acquired().countDown();
            await(probe.release());
            return result;
        }

        private void release(LockProbe probe) {
            if (probe != null) {
                probe.release().countDown();
            }
        }

        private void await(CountDownLatch latch) {
            try {
                if (!latch.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("ROUND 잠금 테스트 해제 신호를 기다리지 못했습니다");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("ROUND 잠금 테스트가 중단되었습니다", exception);
            }
        }
    }

    private record LockProbe(
            CountDownLatch requested,
            CountDownLatch acquired,
            CountDownLatch release
    ) {
    }

    private record SignProbe(CountDownLatch entered, CountDownLatch release) {
    }

    static final class ControllableParticipationGrantSigner
            implements ParticipationGrantSigner {

        private final AtomicReference<SignProbe> nextProbe = new AtomicReference<>();
        private final AtomicInteger signCount = new AtomicInteger();

        SignProbe blockNextSign() {
            SignProbe probe = new SignProbe(new CountDownLatch(1), new CountDownLatch(1));
            if (!nextProbe.compareAndSet(null, probe)) {
                throw new IllegalStateException("이미 다음 참여권 서명 차단이 설정되어 있습니다");
            }
            return probe;
        }

        void reset() {
            SignProbe probe = nextProbe.getAndSet(null);
            if (probe != null) {
                probe.release().countDown();
            }
            signCount.set(0);
        }

        int signCount() {
            return signCount.get();
        }

        @Override
        public String sign(ParticipationGrantClaims claims) {
            signCount.incrementAndGet();
            SignProbe probe = nextProbe.getAndSet(null);
            if (probe != null) {
                probe.entered().countDown();
                try {
                    if (!probe.release().await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("참여권 서명 테스트 해제 신호를 기다리지 못했습니다");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("참여권 서명 테스트가 중단되었습니다", exception);
                }
            }
            return "test-participation-grant";
        }
    }
}
