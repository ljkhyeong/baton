package com.personal.baton.application.roundauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.personal.baton.BatonApplication;
import com.personal.baton.adapter.out.persistence.roundauth.RoundAuthorizationPersistenceAdapter;
import com.personal.baton.application.roundauth.error.RoundParticipationDeniedException;
import com.personal.baton.application.roundauth.port.in.RoundAuthorizationUseCase;
import com.personal.baton.application.roundauth.port.in.RoundAuthorizationUseCase.ClaimMembershipCommand;
import com.personal.baton.application.roundauth.port.in.RoundAuthorizationUseCase.CreateRoomMappingCommand;
import com.personal.baton.application.roundauth.port.in.RoundAuthorizationUseCase.EndRoomMappingCommand;
import com.personal.baton.application.roundauth.port.in.RoundAuthorizationUseCase.RoomMappingResult;
import com.personal.baton.application.roundauth.port.out.RoundAuthorizationRepository;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
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
class RoundAuthorizationPersistenceUseCaseTest {

    private static final String CREATION_KEY = "pilot-operator-key-0000000000000001";
    private static final Instant CREATED_AT = Instant.parse("2026-08-08T10:00:00Z");
    private static final Instant ENDED_AT = Instant.parse("2026-08-08T10:05:00Z");

    @Container
    @ServiceConnection
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
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

    @BeforeEach
    void setUp() {
        mutableClock.setInstant(CREATED_AT);
        roundRepository.resetFailures();
        roomIdGenerator.reset();
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
            assertThat(storedTombstoneCount(fixture.resourceId())).isOne();
            assertThat(storedMappingCount(fixture.resourceId())).isOne();
        } finally {
            executor.shutdownNow();
        }
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

    private RoundFixture createFixture() {
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
        roundAuthorizationUseCase.claimMembership(new ClaimMembershipCommand(
                accountId,
                workspace.teamId(),
                workspace.seasonId(),
                memberId,
                workspace.accessKey()
        ));
        return new RoundFixture(workspace, accountId, resourceId);
    }

    private RoomMappingResult createRoomMapping(RoundFixture fixture) {
        return roundAuthorizationUseCase.createRoomMapping(new CreateRoomMappingCommand(
                fixture.accountId(),
                fixture.workspace().teamId(),
                fixture.workspace().seasonId(),
                fixture.resourceId(),
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

        private FailingRoundAuthorizationRepository(RoundAuthorizationRepository delegate) {
            this.delegate = delegate;
        }

        void failNextMappingDelete() {
            failMappingDelete.set(true);
        }

        void resetFailures() {
            failMappingDelete.set(false);
        }

        @Override
        public AccountTeamMembership saveMembership(AccountTeamMembership membership) {
            return delegate.saveMembership(membership);
        }

        @Override
        public Optional<AccountTeamMembership> findMembership(UUID accountId, UUID teamId) {
            return delegate.findMembership(accountId, teamId);
        }

        @Override
        public Optional<AccountTeamMembership> findMembershipByMemberId(UUID memberId) {
            return delegate.findMembershipByMemberId(memberId);
        }

        @Override
        public RoundRoomTombstone saveTombstone(RoundRoomTombstone tombstone) {
            return delegate.saveTombstone(tombstone);
        }

        @Override
        public Optional<RoundRoomTombstone> findTombstoneForUpdate(String roomId) {
            return delegate.findTombstoneForUpdate(roomId);
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
        public void deleteMapping(RoundRoomMapping mapping) {
            if (failMappingDelete.compareAndSet(true, false)) {
                throw new IllegalStateException("의도한 ROUND 매핑 삭제 실패");
            }
            delegate.deleteMapping(mapping);
        }
    }
}
