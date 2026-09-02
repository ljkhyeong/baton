package com.personal.baton.application.workspace;

import com.personal.baton.application.workspace.port.in.WorkspaceContract;

import com.personal.baton.BatonApplication;
import com.personal.baton.application.workspace.error.WorkspaceAccessKeyConflictException;
import com.personal.baton.application.workspace.error.WorkspaceContentConflictException;
import com.personal.baton.application.workspace.port.in.WorkspaceLifecycleUseCase;
import com.personal.baton.application.workspace.port.in.WorkspacePeopleUseCase;
import com.personal.baton.application.workspace.port.in.WorkspacePeopleCommands.CreateMemberCommand;
import com.personal.baton.application.workspace.port.in.WorkspacePeopleCommands.CreateRoleCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceLifecycleCommands.CreateWorkspaceCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceContract.CreatedWorkspaceResult;
import com.personal.baton.application.workspace.port.in.WorkspaceContract.RoleResult;
import com.personal.baton.application.workspace.port.in.WorkspacePeopleCommands.UpdateRoleCommand;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("usecase")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        classes = BatonApplication.class,
        properties = {
                "baton.workspace.creation-key=pilot-operator-key-0000000000000001",
                "baton.workspace.recovery-key=pilot-recovery-key-0000000000000002",
                "spring.datasource.hikari.connection-timeout=500",
                "spring.datasource.hikari.connection-init-sql=SET SESSION innodb_lock_wait_timeout=1",
                "spring.transaction.default-timeout=3s"
        }
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WorkspaceRequestBudgetTest {

    private static final String CREATION_KEY = "pilot-operator-key-0000000000000001";
    private static final Duration MAXIMUM_REQUEST_DURATION = Duration.ofSeconds(5);

    @Container
    @ServiceConnection
    private static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:b3b90af2a6552ae30c266fdb7d5dd55f3afb72404bb78d37fe8a23eb857fd3fb"
    )
            .withDatabaseName("baton_request_budget")
            .withUsername("baton")
            .withPassword("password");

    @Autowired
    private WorkspaceLifecycleUseCase lifecycleUseCase;

    @Autowired
    private WorkspacePeopleUseCase peopleUseCase;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @DisplayName("역할 행 잠금이 해제되지 않으면 수정은 제한 시간 안에 내용 충돌로 실패하고 원본을 보존한다")
    @Test
    void failsRoleUpdateWithinBudgetAndPreservesOriginalRole() throws Exception {
        CreatedWorkspaceResult created = createWorkspace(
                "workspace-request-budget-role-0001",
                "요청 예산 역할 스터디"
        );
        RoleResult role = peopleUseCase.createRole(
                created.teamId(),
                created.seasonId(),
                "content-request-budget-role-000000000001",
                created.accessKey(),
                new CreateRoleCommand(
                        "진행자",
                        "모임 흐름을 관리합니다",
                        null,
                        null,
                        null,
                        null,
                        List.of("안건 정리"),
                        null
                )
        );

        assertThat(jdbcTemplate.queryForObject(
                "SELECT @@SESSION.innodb_lock_wait_timeout",
                Long.class
        )).isEqualTo(1L);
        assertThat(transactionManager).isInstanceOfSatisfying(
                AbstractPlatformTransactionManager.class,
                manager -> assertThat(manager.getDefaultTimeout()).isEqualTo(3)
        );

        try (HeldDatabaseLock ignored = holdExclusiveRowLock(
                "SELECT HEX(id) FROM roles WHERE id = UUID_TO_BIN(?) FOR UPDATE",
                role.id()
        )) {
            long startedAt = System.nanoTime();

            assertThatThrownBy(() -> peopleUseCase.updateRole(
                    created.teamId(),
                    created.seasonId(),
                    role.id(),
                    created.accessKey(),
                    new UpdateRoleCommand(
                            "메인 진행자",
                            "잠금 대기 뒤에는 저장되지 않아야 합니다",
                            null,
                            null,
                            null,
                            null,
                            List.of("안건 정리", "시간 관리"),
                            null
                    )
            )).isInstanceOfSatisfying(
                    WorkspaceContentConflictException.class,
                    exception -> assertThat(exception.getCause())
                            .isInstanceOf(PessimisticLockingFailureException.class)
            );

            assertThat(elapsedSince(startedAt)).isLessThan(MAXIMUM_REQUEST_DURATION);
        }

        assertThat(lifecycleUseCase.getWorkspace(
                created.teamId(),
                created.seasonId(),
                created.accessKey()
        ).roles()).filteredOn(savedRole -> savedRole.id().equals(role.id()))
                .singleElement()
                .satisfies(savedRole -> {
                    assertThat(savedRole.name()).isEqualTo("진행자");
                    assertThat(savedRole.purpose()).isEqualTo("모임 흐름을 관리합니다");
                    assertThat(savedRole.responsibilities()).containsExactly("안건 정리");
                });
    }

    @DisplayName("팀 행의 배타 잠금이 유지되면 구성원 생성은 제한 시간 안에 접근 키 충돌로 실패하고 생성을 롤백한다")
    @Test
    void failsMemberCreationWithinBudgetAndRollsBackCreation() throws Exception {
        CreatedWorkspaceResult created = createWorkspace(
                "workspace-request-budget-team-0001",
                "요청 예산 팀 스터디"
        );
        int memberCountBefore = memberCount(created.teamId());
        int reservationCountBefore = contentReservationCount(created.teamId());

        try (HeldDatabaseLock ignored = holdExclusiveRowLock(
                "SELECT HEX(id) FROM teams WHERE id = UUID_TO_BIN(?) FOR UPDATE",
                created.teamId()
        )) {
            long startedAt = System.nanoTime();

            assertThatThrownBy(() -> peopleUseCase.createMember(
                    created.teamId(),
                    created.seasonId(),
                    "content-request-budget-member-0000000001",
                    created.accessKey(),
                    new CreateMemberCommand("박민서")
            )).isInstanceOfSatisfying(
                    WorkspaceAccessKeyConflictException.class,
                    exception -> assertThat(exception.getCause())
                            .isInstanceOf(PessimisticLockingFailureException.class)
            );

            assertThat(elapsedSince(startedAt)).isLessThan(MAXIMUM_REQUEST_DURATION);
        }

        assertThat(memberCount(created.teamId())).isEqualTo(memberCountBefore);
        assertThat(contentReservationCount(created.teamId())).isEqualTo(reservationCountBefore);
        assertThat(lifecycleUseCase.getWorkspace(
                created.teamId(),
                created.seasonId(),
                created.accessKey()
        ).members()).noneMatch(member -> member.name().equals("박민서"));
    }

    private CreatedWorkspaceResult createWorkspace(String idempotencyKey, String teamName) {
        return lifecycleUseCase.createWorkspace(
                idempotencyKey,
                CREATION_KEY,
                new CreateWorkspaceCommand(
                        teamName,
                        "파일럿 시즌",
                        LocalDate.of(2026, 7, 21),
                        LocalDate.of(2026, 8, 31),
                        List.of("김준호")
                )
        );
    }

    private HeldDatabaseLock holdExclusiveRowLock(String sql, UUID id) throws Exception {
        CountDownLatch lockAcquired = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.setTimeout(10);
        Future<?> lockHolder = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.queryForObject(sql, String.class, id.toString());
            lockAcquired.countDown();
            await(releaseLock);
        }));

        if (!lockAcquired.await(10, TimeUnit.SECONDS)) {
            releaseLock.countDown();
            executor.shutdownNow();
            throw new IllegalStateException("테스트용 데이터베이스 행 잠금을 획득하지 못했습니다");
        }
        return new HeldDatabaseLock(releaseLock, lockHolder, executor);
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("테스트용 데이터베이스 행 잠금 해제를 기다리지 못했습니다");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("테스트용 데이터베이스 행 잠금 대기가 중단되었습니다", exception);
        }
    }

    private Duration elapsedSince(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt);
    }

    private int memberCount(UUID teamId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM members WHERE team_id = UUID_TO_BIN(?)",
                Integer.class,
                teamId.toString()
        );
    }

    private int contentReservationCount(UUID teamId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM content_creation_idempotency WHERE team_id = UUID_TO_BIN(?)",
                Integer.class,
                teamId.toString()
        );
    }

    private record HeldDatabaseLock(
            CountDownLatch releaseLock,
            Future<?> lockHolder,
            ExecutorService executor
    ) implements AutoCloseable {

        @Override
        public void close() throws Exception {
            releaseLock.countDown();
            try {
                lockHolder.get(10, TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
                if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("테스트용 잠금 실행기를 종료하지 못했습니다");
                }
            }
        }
    }
}
