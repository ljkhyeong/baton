package com.personal.baton.application.workspace;

import com.personal.baton.BatonApplication;
import com.personal.baton.application.workspace.error.IdempotencyKeyConflictException;
import com.personal.baton.application.workspace.error.IdempotencyKeyReusedException;
import com.personal.baton.application.workspace.error.IdempotencyReplayExpiredException;
import com.personal.baton.application.workspace.error.RoleNameConflictException;
import com.personal.baton.application.workspace.error.WorkspaceAccessDeniedException;
import com.personal.baton.application.workspace.error.WorkspaceAccessKeyConflictException;
import com.personal.baton.application.workspace.error.WorkspaceCreationDeniedException;
import com.personal.baton.application.workspace.error.WorkspaceNotFoundException;
import com.personal.baton.application.workspace.error.WorkspaceRecoveryDeniedException;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.CreateDecisionCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.CreateHandoffItemCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.CreateRoleCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.CreateRoutineCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.CreateWorkspaceCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.CreatedWorkspaceResult;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.DecisionResult;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.HandoffItemResult;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.MemberResult;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.RoleResult;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.RoutineResult;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.WorkspaceResult;
import com.personal.baton.application.workspace.port.out.WorkspaceRepository;
import com.personal.baton.domain.workspace.DomainValidationException;
import com.personal.baton.domain.workspace.HandoffCategory;
import com.personal.baton.domain.workspace.RoutinePhase;
import com.personal.baton.domain.workspace.RoutineStatus;
import com.personal.baton.domain.workspace.Team;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

@Tag("usecase")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        classes = {BatonApplication.class, WorkspaceUseCaseTest.FixedClockConfiguration.class},
        properties = {
                "spring.jpa.properties.hibernate.generate_statistics=true",
                "baton.workspace.creation-key=pilot-operator-key",
                "baton.workspace.recovery-key=pilot-recovery-key"
        }
)
class WorkspaceUseCaseTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-07-20T03:04:05Z");
    private static final String CREATION_KEY = "pilot-operator-key";
    private static final String RECOVERY_KEY = "pilot-recovery-key";
    private static final String PRIMARY_IDEMPOTENCY_KEY = "workspace-idempotency-primary-000001";

    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("baton")
            .withUsername("baton")
            .withPassword("password");

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private WorkspaceUseCase workspaceUseCase;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @DisplayName("워크스페이스 생성부터 모든 기록과 완료 처리까지 저장하고 접근 키와 projection 계약을 지킨다")
    @Test
    void persistsCompleteWorkspaceFlowAndEnforcesAccessKey() {
        CreatedWorkspaceResult created = workspaceUseCase.createWorkspace(
                PRIMARY_IDEMPOTENCY_KEY,
                CREATION_KEY,
                new CreateWorkspaceCommand(
                        "알고리즘 한 바퀴",
                        "2026 여름 시즌",
                        LocalDate.of(2026, 7, 2),
                        LocalDate.of(2026, 9, 17),
                        List.of("김준호", "박민서")
                )
        );

        assertThat(created.accessKey()).matches("[A-Za-z0-9_-]{43}");
        String storedHash = jdbcTemplate.queryForObject(
                "SELECT access_key_hash FROM teams WHERE name = ?",
                String.class,
                "알고리즘 한 바퀴"
        );
        assertThat(storedHash)
                .matches("[0-9a-f]{64}")
                .isNotEqualTo(created.accessKey());

        WorkspaceResult initial = workspaceUseCase.getWorkspace(
                created.teamId(), created.seasonId(), created.accessKey());
        MemberResult minseo = memberNamed(initial, "박민서");
        MemberResult junho = memberNamed(initial, "김준호");

        RoleResult role = workspaceUseCase.createRole(
                created.teamId(),
                created.seasonId(),
                contentIdempotencyKey("role-question-curator"),
                created.accessKey(),
                new CreateRoleCommand(
                        "질문 큐레이터",
                        "막힌 지점을 모아 함께 풉니다",
                        minseo.id(),
                        junho.id(),
                        LocalDate.of(2026, 7, 20),
                        LocalDate.of(2026, 9, 17),
                        List.of("질문 수집", "공통 막힘 정리"),
                        "질문이 개인 메모에만 남을 수 있습니다"
                )
        );

        assertThatThrownBy(() -> workspaceUseCase.createRole(
                created.teamId(),
                created.seasonId(),
                contentIdempotencyKey("role-duplicate-name"),
                created.accessKey(),
                new CreateRoleCommand(
                        "질문 큐레이터",
                        "중복 역할",
                        null,
                        null,
                        null,
                        null,
                        List.of(),
                        null
                )
        )).isInstanceOf(RoleNameConflictException.class);

        RoleResult recorderRole = workspaceUseCase.createRole(
                created.teamId(),
                created.seasonId(),
                contentIdempotencyKey("role-recorder"),
                created.accessKey(),
                new CreateRoleCommand(
                        "기록자",
                        "결정과 맥락을 남깁니다",
                        junho.id(),
                        minseo.id(),
                        LocalDate.of(2026, 7, 20),
                        LocalDate.of(2026, 9, 17),
                        List.of("결정 기록"),
                        null
                )
        );

        RoutineResult routine = workspaceUseCase.createRoutine(
                created.teamId(),
                created.seasonId(),
                contentIdempotencyKey("routine-question-collection"),
                created.accessKey(),
                new CreateRoutineCommand(
                        "모임 전 질문 모으기",
                        RoutinePhase.BEFORE,
                        "모임 하루 전",
                        role.id(),
                        "공통 질문을 한 문서에 정리합니다"
                )
        );
        RoutineResult completedRoutine = workspaceUseCase.updateRoutineCompletion(
                created.teamId(), created.seasonId(), routine.id(), created.accessKey(), true);
        assertThat(completedRoutine.status()).isEqualTo(RoutineStatus.DONE);

        DecisionResult decision = workspaceUseCase.createDecision(
                created.teamId(),
                created.seasonId(),
                contentIdempotencyKey("decision-question-deadline"),
                created.accessKey(),
                new CreateDecisionCommand(
                        "질문은 모임 전날 마감한다",
                        "진행자가 준비할 시간을 확보합니다",
                        "모임 당일에도 받는 방안을 검토했습니다",
                        minseo.id(),
                        List.of(role.id())
                )
        );
        assertThat(decision.createdAt()).isEqualTo(FIXED_INSTANT);
        assertThat(decision.authorName()).isEqualTo("박민서");

        workspaceUseCase.createDecision(
                created.teamId(),
                created.seasonId(),
                contentIdempotencyKey("decision-recorder-ownership"),
                created.accessKey(),
                new CreateDecisionCommand(
                        "결정 기록은 기록자가 정리한다",
                        "책임을 분명히 합니다",
                        "진행자가 함께 작성하는 방안을 검토했습니다",
                        junho.id(),
                        List.of(role.id(), recorderRole.id())
                )
        );

        assertThatThrownBy(() -> workspaceUseCase.createDecision(
                created.teamId(),
                created.seasonId(),
                contentIdempotencyKey("decision-duplicate-role"),
                created.accessKey(),
                new CreateDecisionCommand(
                        "중복 역할 결정",
                        "같은 역할을 두 번 연결할 수 없습니다",
                        "",
                        minseo.id(),
                        List.of(role.id(), role.id())
                )
        )).isInstanceOfSatisfying(
                DomainValidationException.class,
                exception -> assertThat(exception.getMessage()).isEqualTo("관련 역할은 중복될 수 없습니다")
        );

        HandoffItemResult handoffItem = workspaceUseCase.createHandoffItem(
                created.teamId(),
                created.seasonId(),
                contentIdempotencyKey("handoff-document-access"),
                created.accessKey(),
                new CreateHandoffItemCommand(
                        role.id(),
                        "질문 목록 문서 권한 넘기기",
                        HandoffCategory.RESOURCE
                )
        );
        HandoffItemResult completedItem = workspaceUseCase.updateHandoffItemCompletion(
                created.teamId(), created.seasonId(), handoffItem.id(), created.accessKey(), true);
        assertThat(completedItem.completed()).isTrue();

        assertThatThrownBy(() -> workspaceUseCase.getWorkspace(
                created.teamId(), created.seasonId(), "wrong-access-key"))
                .isInstanceOf(WorkspaceAccessDeniedException.class);

        assertThatThrownBy(() -> workspaceUseCase.getWorkspace(
                UUID.randomUUID(), created.seasonId(), created.accessKey()))
                .isInstanceOfSatisfying(WorkspaceNotFoundException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("TEAM_NOT_FOUND"));

        assertThatThrownBy(() -> workspaceUseCase.getWorkspace(
                created.teamId(), UUID.randomUUID(), created.accessKey()))
                .isInstanceOfSatisfying(WorkspaceNotFoundException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("SEASON_NOT_FOUND"));

        assertThatThrownBy(() -> workspaceUseCase.updateRoutineCompletion(
                created.teamId(), created.seasonId(), UUID.randomUUID(), created.accessKey(), true))
                .isInstanceOfSatisfying(WorkspaceNotFoundException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("ROUTINE_NOT_FOUND"));

        assertThatThrownBy(() -> workspaceUseCase.createDecision(
                created.teamId(),
                created.seasonId(),
                contentIdempotencyKey("decision-invalid-author"),
                created.accessKey(),
                new CreateDecisionCommand(
                        "잘못된 작성자",
                        "팀 구성원이 아닙니다",
                        "",
                        UUID.randomUUID(),
                        List.of(role.id())
                )
        )).isInstanceOfSatisfying(WorkspaceNotFoundException.class,
                exception -> assertThat(exception.getCode()).isEqualTo("MEMBER_NOT_FOUND"));

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        WorkspaceResult reloaded = workspaceUseCase.getWorkspace(
                created.teamId(), created.seasonId(), created.accessKey());

        assertThat(statistics.getPrepareStatementCount()).isEqualTo(7);
        assertThat(reloaded.team().name()).isEqualTo("알고리즘 한 바퀴");
        assertThat(reloaded.season().startDate()).isEqualTo(LocalDate.of(2026, 7, 2));
        assertThat(reloaded.members()).extracting(MemberResult::name)
                .containsExactly("김준호", "박민서");
        assertThat(reloaded.roles()).filteredOn(savedRole -> savedRole.name().equals("질문 큐레이터"))
                .singleElement().satisfies(savedRole -> {
            assertThat(savedRole.responsibilities()).containsExactly("질문 수집", "공통 막힘 정리");
            assertThat(savedRole.currentMemberId()).isEqualTo(minseo.id());
            assertThat(savedRole.nextMemberId()).isEqualTo(junho.id());
        });
        assertThat(reloaded.roles()).filteredOn(savedRole -> savedRole.name().equals("기록자"))
                .singleElement().satisfies(savedRole ->
                        assertThat(savedRole.responsibilities()).containsExactly("결정 기록"));
        assertThat(reloaded.routines()).singleElement().satisfies(savedRoutine ->
                assertThat(savedRoutine.status()).isEqualTo(RoutineStatus.DONE));
        assertThat(reloaded.decisions())
                .filteredOn(savedDecision -> savedDecision.title().equals("질문은 모임 전날 마감한다"))
                .singleElement().satisfies(savedDecision -> {
            assertThat(savedDecision.createdAt()).isEqualTo(FIXED_INSTANT);
            assertThat(savedDecision.authorName()).isEqualTo("박민서");
            assertThat(savedDecision.roleIds()).containsExactly(role.id());
        });
        assertThat(reloaded.decisions())
                .filteredOn(savedDecision -> savedDecision.title().equals("결정 기록은 기록자가 정리한다"))
                .singleElement().satisfies(savedDecision ->
                        assertThat(savedDecision.roleIds()).containsExactly(role.id(), recorderRole.id()));
        assertThat(reloaded.handoffItems()).singleElement().satisfies(savedItem -> {
            assertThat(savedItem.category()).isEqualTo(HandoffCategory.RESOURCE);
            assertThat(savedItem.completed()).isTrue();
        });

        CreatedWorkspaceResult otherWorkspace = workspaceUseCase.createWorkspace(
                "workspace-idempotency-other-0000001",
                CREATION_KEY,
                new CreateWorkspaceCommand(
                        "다른 팀",
                        "다른 시즌",
                        LocalDate.of(2026, 7, 20),
                        LocalDate.of(2026, 8, 20),
                        List.of("다른 구성원")
                )
        );
        assertThatThrownBy(() -> workspaceUseCase.createRoutine(
                otherWorkspace.teamId(),
                otherWorkspace.seasonId(),
                contentIdempotencyKey("routine-cross-team-role"),
                otherWorkspace.accessKey(),
                new CreateRoutineCommand(
                        "교차 팀 루틴",
                        RoutinePhase.DURING,
                        "모임 중",
                        role.id(),
                        "다른 팀 역할을 참조할 수 없습니다"
                )
        )).isInstanceOfSatisfying(WorkspaceNotFoundException.class,
                exception -> assertThat(exception.getCode()).isEqualTo("ROLE_NOT_FOUND"));
    }

    @DisplayName("같은 콘텐츠 멱등 키는 네 작업에서 독립적으로 재생되고 다른 요청 재사용은 거절된다")
    @Test
    void replaysContentCreationsWithOperationSeparationAndNormalizedFingerprints() {
        CreatedWorkspaceResult created = workspaceUseCase.createWorkspace(
                "workspace-content-idempotency-flow-0001",
                CREATION_KEY,
                new CreateWorkspaceCommand(
                        "콘텐츠 멱등 스터디",
                        "파일럿 시즌",
                        LocalDate.of(2026, 7, 21),
                        LocalDate.of(2026, 8, 31),
                        List.of("박민서")
                )
        );
        MemberResult member = workspaceUseCase.getWorkspace(
                created.teamId(), created.seasonId(), created.accessKey()).members().getFirst();
        String sharedRawKey = "content-shared-across-operations-00001";

        RoleResult role = workspaceUseCase.createRole(
                created.teamId(),
                created.seasonId(),
                sharedRawKey,
                created.accessKey(),
                new CreateRoleCommand(
                        "  진행자  ",
                        "  모임 흐름을 관리합니다  ",
                        member.id(),
                        null,
                        LocalDate.of(2026, 7, 21),
                        null,
                        List.of("  질문 순서 정리  ", "시간 확인"),
                        "   "
                )
        );
        RoleResult roleReplay = workspaceUseCase.createRole(
                created.teamId(),
                created.seasonId(),
                sharedRawKey,
                created.accessKey(),
                new CreateRoleCommand(
                        "진행자",
                        "모임 흐름을 관리합니다",
                        member.id(),
                        null,
                        LocalDate.of(2026, 7, 21),
                        null,
                        List.of("질문 순서 정리", "시간 확인"),
                        null
                )
        );

        RoutineResult routine = workspaceUseCase.createRoutine(
                created.teamId(),
                created.seasonId(),
                sharedRawKey,
                created.accessKey(),
                new CreateRoutineCommand(
                        "  질문 취합  ",
                        RoutinePhase.BEFORE,
                        "  모임 하루 전  ",
                        role.id(),
                        "  공통 문서에 질문을 모읍니다  "
                )
        );
        workspaceUseCase.updateRoutineCompletion(
                created.teamId(), created.seasonId(), routine.id(), created.accessKey(), true);
        RoutineResult routineReplay = workspaceUseCase.createRoutine(
                created.teamId(),
                created.seasonId(),
                sharedRawKey,
                created.accessKey(),
                new CreateRoutineCommand(
                        "질문 취합",
                        RoutinePhase.BEFORE,
                        "모임 하루 전",
                        role.id(),
                        "공통 문서에 질문을 모읍니다"
                )
        );

        DecisionResult decision = workspaceUseCase.createDecision(
                created.teamId(),
                created.seasonId(),
                sharedRawKey,
                created.accessKey(),
                new CreateDecisionCommand(
                        "  질문은 전날 마감한다  ",
                        "  준비 시간을 확보합니다  ",
                        "   ",
                        member.id(),
                        List.of(role.id())
                )
        );
        DecisionResult decisionReplay = workspaceUseCase.createDecision(
                created.teamId(),
                created.seasonId(),
                sharedRawKey,
                created.accessKey(),
                new CreateDecisionCommand(
                        "질문은 전날 마감한다",
                        "준비 시간을 확보합니다",
                        null,
                        member.id(),
                        List.of(role.id())
                )
        );

        HandoffItemResult handoff = workspaceUseCase.createHandoffItem(
                created.teamId(),
                created.seasonId(),
                sharedRawKey,
                created.accessKey(),
                new CreateHandoffItemCommand(
                        role.id(),
                        "  질문 문서 권한 넘기기  ",
                        HandoffCategory.RESOURCE
                )
        );
        workspaceUseCase.updateHandoffItemCompletion(
                created.teamId(), created.seasonId(), handoff.id(), created.accessKey(), true);
        HandoffItemResult handoffReplay = workspaceUseCase.createHandoffItem(
                created.teamId(),
                created.seasonId(),
                sharedRawKey,
                created.accessKey(),
                new CreateHandoffItemCommand(
                        role.id(),
                        "질문 문서 권한 넘기기",
                        HandoffCategory.RESOURCE
                )
        );

        assertThat(roleReplay).isEqualTo(role);
        assertThat(routineReplay.id()).isEqualTo(routine.id());
        assertThat(routineReplay.status()).isEqualTo(RoutineStatus.DONE);
        assertThat(decisionReplay).isEqualTo(decision);
        assertThat(handoffReplay.id()).isEqualTo(handoff.id());
        assertThat(handoffReplay.completed()).isTrue();
        assertThatThrownBy(() -> workspaceUseCase.createRole(
                created.teamId(),
                created.seasonId(),
                sharedRawKey,
                created.accessKey(),
                new CreateRoleCommand(
                        "진행자",
                        "다른 역할 목적입니다",
                        member.id(),
                        null,
                        LocalDate.of(2026, 7, 21),
                        null,
                        List.of("질문 순서 정리", "시간 확인"),
                        null
                )
        )).isInstanceOf(IdempotencyKeyReusedException.class);
        assertThatThrownBy(() -> workspaceUseCase.createRoutine(
                created.teamId(),
                created.seasonId(),
                sharedRawKey,
                created.accessKey(),
                new CreateRoutineCommand(
                        "질문 취합",
                        RoutinePhase.BEFORE,
                        "모임 하루 전",
                        UUID.randomUUID(),
                        "공통 문서에 질문을 모읍니다"
                )
        )).isInstanceOf(IdempotencyKeyReusedException.class);
        assertThatThrownBy(() -> workspaceUseCase.createDecision(
                created.teamId(),
                created.seasonId(),
                sharedRawKey,
                created.accessKey(),
                new CreateDecisionCommand(
                        "다른 결정 제목입니다",
                        "준비 시간을 확보합니다",
                        null,
                        member.id(),
                        List.of(role.id())
                )
        )).isInstanceOf(IdempotencyKeyReusedException.class);
        assertThatThrownBy(() -> workspaceUseCase.createHandoffItem(
                created.teamId(),
                created.seasonId(),
                sharedRawKey,
                created.accessKey(),
                new CreateHandoffItemCommand(
                        role.id(),
                        "질문 문서 권한 넘기기",
                        HandoffCategory.ADVICE
                )
        )).isInstanceOf(IdempotencyKeyReusedException.class);
        assertThatThrownBy(() -> workspaceUseCase.createRole(
                created.teamId(),
                created.seasonId(),
                sharedRawKey,
                "wrong-access-key",
                new CreateRoleCommand(
                        "진행자",
                        "모임 흐름을 관리합니다",
                        member.id(),
                        null,
                        LocalDate.of(2026, 7, 21),
                        null,
                        List.of("질문 순서 정리", "시간 확인"),
                        null
                )
        )).isInstanceOf(WorkspaceAccessDeniedException.class);

        List<String> storedHashes = jdbcTemplate.queryForList(
                "SELECT idempotency_hash FROM content_creation_idempotency "
                        + "WHERE team_id = UUID_TO_BIN(?)",
                String.class,
                created.teamId().toString()
        );
        assertThat(storedHashes)
                .hasSize(4)
                .doesNotHaveDuplicates()
                .allSatisfy(hash -> assertThat(hash)
                        .matches("[0-9a-f]{64}")
                        .doesNotContain(sharedRawKey));
        assertThat(jdbcTemplate.queryForList(
                "SELECT operation FROM content_creation_idempotency "
                        + "WHERE team_id = UUID_TO_BIN(?) ORDER BY operation",
                String.class,
                created.teamId().toString()
        )).containsExactly("DECISION", "HANDOFF_ITEM", "ROLE", "ROUTINE");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM routines WHERE season_id = UUID_TO_BIN(?)",
                Integer.class,
                created.seasonId().toString()
        )).isEqualTo(1);
    }

    @DisplayName("권한과 참조 및 역할 이름 검증에 실패한 콘텐츠 생성은 멱등 예약을 남기지 않는다")
    @Test
    void rollsBackContentIdempotencyReservationWhenCreationFails() {
        CreatedWorkspaceResult created = workspaceUseCase.createWorkspace(
                "workspace-content-reservation-rollback-01",
                CREATION_KEY,
                new CreateWorkspaceCommand(
                        "예약 롤백 스터디",
                        "파일럿 시즌",
                        LocalDate.of(2026, 7, 21),
                        LocalDate.of(2026, 8, 31),
                        List.of("박민서")
                )
        );
        String roleKey = contentIdempotencyKey("rollback-role-original");
        workspaceUseCase.createRole(
                created.teamId(),
                created.seasonId(),
                roleKey,
                created.accessKey(),
                new CreateRoleCommand("진행자", "진행합니다", null, null, null, null, List.of(), null)
        );
        int reservationsBefore = contentReservationCount(created.teamId());

        assertThatThrownBy(() -> workspaceUseCase.createRoutine(
                created.teamId(),
                created.seasonId(),
                contentIdempotencyKey("rollback-invalid-role"),
                created.accessKey(),
                new CreateRoutineCommand(
                        "잘못된 루틴",
                        RoutinePhase.BEFORE,
                        "모임 전",
                        UUID.randomUUID(),
                        "존재하지 않는 역할입니다"
                )
        )).isInstanceOfSatisfying(
                WorkspaceNotFoundException.class,
                exception -> assertThat(exception.getCode()).isEqualTo("ROLE_NOT_FOUND")
        );
        assertThatThrownBy(() -> workspaceUseCase.createRole(
                created.teamId(),
                created.seasonId(),
                contentIdempotencyKey("rollback-duplicate-name"),
                created.accessKey(),
                new CreateRoleCommand("진행자", "중복입니다", null, null, null, null, List.of(), null)
        )).isInstanceOf(RoleNameConflictException.class);
        assertThatThrownBy(() -> workspaceUseCase.createHandoffItem(
                created.teamId(),
                created.seasonId(),
                contentIdempotencyKey("rollback-wrong-access"),
                "wrong-access-key",
                new CreateHandoffItemCommand(UUID.randomUUID(), "잘못된 요청", HandoffCategory.ADVICE)
        )).isInstanceOf(WorkspaceAccessDeniedException.class);

        assertThat(contentReservationCount(created.teamId())).isEqualTo(reservationsBefore);
    }

    @DisplayName("같은 루틴 생성 멱등 키의 두 트랜잭션이 겹치면 한 건만 저장되고 재시도할 수 있다")
    @Test
    void serializesConcurrentRoutineCreationWithDatabaseReservation() throws Exception {
        CreatedWorkspaceResult created = workspaceUseCase.createWorkspace(
                "workspace-content-concurrent-routine-01",
                CREATION_KEY,
                new CreateWorkspaceCommand(
                        "동시 루틴 스터디",
                        "파일럿 시즌",
                        LocalDate.of(2026, 7, 21),
                        LocalDate.of(2026, 8, 31),
                        List.of("박민서")
                )
        );
        RoleResult role = workspaceUseCase.createRole(
                created.teamId(),
                created.seasonId(),
                contentIdempotencyKey("concurrent-routine-owner"),
                created.accessKey(),
                new CreateRoleCommand("진행자", "진행합니다", null, null, null, null, List.of(), null)
        );
        String idempotencyKey = contentIdempotencyKey("concurrent-routine-request");
        CreateRoutineCommand command = new CreateRoutineCommand(
                "동시 요청 루틴",
                RoutinePhase.DURING,
                "모임 중",
                role.id(),
                "한 번만 생성되어야 합니다"
        );
        CyclicBarrier bothRequestsReadNoExistingReservation = new CyclicBarrier(2);
        WorkspaceRepository synchronizedRepository = mock(
                WorkspaceRepository.class,
                delegatesTo(workspaceRepository)
        );
        doAnswer(invocation -> {
            Object existing = workspaceRepository.findContentCreationIdempotency(
                    invocation.getArgument(0),
                    invocation.getArgument(1)
            );
            bothRequestsReadNoExistingReservation.await(10, TimeUnit.SECONDS);
            return existing;
        }).when(synchronizedRepository).findContentCreationIdempotency(any(UUID.class), anyString());
        WorkspaceService synchronizedService = new WorkspaceService(
                synchronizedRepository,
                Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC),
                CREATION_KEY,
                RECOVERY_KEY
        );
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Object> first = executor.submit(() -> runRoutineCreationTransaction(
                    transactionTemplate,
                    synchronizedService,
                    created,
                    idempotencyKey,
                    command
            ));
            Future<Object> second = executor.submit(() -> runRoutineCreationTransaction(
                    transactionTemplate,
                    synchronizedService,
                    created,
                    idempotencyKey,
                    command
            ));
            List<Object> outcomes = List.of(
                    first.get(30, TimeUnit.SECONDS),
                    second.get(30, TimeUnit.SECONDS)
            );
            List<RoutineResult> committed = outcomes.stream()
                    .filter(RoutineResult.class::isInstance)
                    .map(RoutineResult.class::cast)
                    .toList();
            List<IdempotencyKeyConflictException> conflicts = outcomes.stream()
                    .filter(IdempotencyKeyConflictException.class::isInstance)
                    .map(IdempotencyKeyConflictException.class::cast)
                    .toList();

            assertThat(committed).hasSize(1);
            assertThat(conflicts).hasSize(1);
            RoutineResult replay = workspaceUseCase.createRoutine(
                    created.teamId(),
                    created.seasonId(),
                    idempotencyKey,
                    created.accessKey(),
                    command
            );
            assertThat(replay).isEqualTo(committed.getFirst());
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM routines WHERE season_id = UUID_TO_BIN(?) AND title = ?",
                    Integer.class,
                    created.seasonId().toString(),
                    "동시 요청 루틴"
            )).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @DisplayName("같은 멱등 키와 정규화된 생성 요청은 최초 응답을 복원하고 다른 요청 재사용은 거절한다")
    @Test
    void restoresOriginalWorkspaceForSequentialIdempotentRetry() {
        int teamsBefore = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM teams", Integer.class);
        CreateWorkspaceCommand firstCommand = new CreateWorkspaceCommand(
                "  응답 복구 스터디  ",
                "  여름 시즌  ",
                LocalDate.of(2026, 7, 21),
                LocalDate.of(2026, 8, 31),
                List.of("  박민서  ", "김준호")
        );
        String idempotencyKey = "workspace-idempotency-retry-000001";

        CreatedWorkspaceResult first = workspaceUseCase.createWorkspace(
                idempotencyKey,
                CREATION_KEY,
                firstCommand
        );
        jdbcTemplate.update(
                "INSERT INTO seasons (id, team_id, name, start_date, end_date) "
                        + "VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?, ?)",
                "00000000-0000-0000-0000-000000000001",
                first.teamId().toString(),
                "추가 시즌",
                LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 12, 31)
        );
        CreatedWorkspaceResult retry = workspaceUseCase.createWorkspace(
                idempotencyKey,
                CREATION_KEY,
                new CreateWorkspaceCommand(
                        "응답 복구 스터디",
                        "여름 시즌",
                        LocalDate.of(2026, 7, 21),
                        LocalDate.of(2026, 8, 31),
                        List.of("김준호", "박민서")
                )
        );

        assertThat(retry).isEqualTo(first);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT BIN_TO_UUID(creation_season_id) FROM teams WHERE name = ?",
                String.class,
                "응답 복구 스터디"
        )).isEqualTo(first.seasonId().toString());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM teams", Integer.class))
                .isEqualTo(teamsBefore + 1);
        assertThat(jdbcTemplate.queryForMap(
                "SELECT access_key_hash, idempotency_key_hash, creation_request_fingerprint "
                        + "FROM teams WHERE name = ?",
                "응답 복구 스터디"
        )).allSatisfy((column, value) -> assertThat(value.toString())
                .matches("[0-9a-f]{64}")
                .doesNotContain(first.accessKey())
                .doesNotContain(idempotencyKey));

        assertThatThrownBy(() -> workspaceUseCase.createWorkspace(
                idempotencyKey,
                CREATION_KEY,
                new CreateWorkspaceCommand(
                        "다른 요청",
                        "여름 시즌",
                        LocalDate.of(2026, 7, 21),
                        LocalDate.of(2026, 8, 31),
                        List.of("박민서", "김준호")
                )
        )).isInstanceOf(IdempotencyKeyReusedException.class);
    }

    @DisplayName("공백을 정리한 구성원 이름이 중복되면 팀과 구성원을 저장하지 않는다")
    @Test
    void rejectsNormalizedDuplicateMemberNamesBeforePersistence() {
        int teamsBefore = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM teams", Integer.class);
        int membersBefore = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM members", Integer.class);

        assertThatThrownBy(() -> workspaceUseCase.createWorkspace(
                "workspace-idempotency-duplicate-members-01",
                CREATION_KEY,
                new CreateWorkspaceCommand(
                        "중복 구성원 스터디",
                        "파일럿 시즌",
                        LocalDate.of(2026, 7, 21),
                        LocalDate.of(2026, 8, 31),
                        List.of("박민서", "  박민서  ")
                )
        )).isInstanceOfSatisfying(
                DomainValidationException.class,
                exception -> assertThat(exception.getMessage()).isEqualTo("구성원 이름은 중복될 수 없습니다")
        );

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM teams", Integer.class))
                .isEqualTo(teamsBefore);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM members", Integer.class))
                .isEqualTo(membersBefore);
    }

    @DisplayName("같은 생성 멱등 키의 두 트랜잭션이 겹치면 하나는 충돌하고 재시도는 최초 결과를 복원한다")
    @Test
    void mapsConcurrentCreationConstraintAndRestoresTheCommittedResult() throws Exception {
        String idempotencyKey = "workspace-idempotency-concurrent-create-01";
        CreateWorkspaceCommand command = new CreateWorkspaceCommand(
                "동시 생성 스터디",
                "파일럿 시즌",
                LocalDate.of(2026, 7, 21),
                LocalDate.of(2026, 8, 31),
                List.of("박민서", "김준호")
        );
        CyclicBarrier bothRequestsReadNoExistingTeam = new CyclicBarrier(2);
        WorkspaceRepository synchronizedRepository = mock(
                WorkspaceRepository.class,
                delegatesTo(workspaceRepository)
        );
        doAnswer(invocation -> {
            Object existing = workspaceRepository.findTeamByIdempotencyKeyHash(invocation.getArgument(0));
            bothRequestsReadNoExistingTeam.await(10, TimeUnit.SECONDS);
            return existing;
        }).when(synchronizedRepository).findTeamByIdempotencyKeyHash(anyString());
        WorkspaceService synchronizedService = new WorkspaceService(
                synchronizedRepository,
                Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC),
                CREATION_KEY,
                RECOVERY_KEY
        );
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Object> first = executor.submit(() -> runCreationTransaction(
                    transactionTemplate,
                    synchronizedService,
                    idempotencyKey,
                    command
            ));
            Future<Object> second = executor.submit(() -> runCreationTransaction(
                    transactionTemplate,
                    synchronizedService,
                    idempotencyKey,
                    command
            ));
            List<Object> outcomes = List.of(
                    first.get(30, TimeUnit.SECONDS),
                    second.get(30, TimeUnit.SECONDS)
            );
            List<CreatedWorkspaceResult> committed = outcomes.stream()
                    .filter(CreatedWorkspaceResult.class::isInstance)
                    .map(CreatedWorkspaceResult.class::cast)
                    .toList();
            List<IdempotencyKeyConflictException> conflicts = outcomes.stream()
                    .filter(IdempotencyKeyConflictException.class::isInstance)
                    .map(IdempotencyKeyConflictException.class::cast)
                    .toList();

            assertThat(committed).hasSize(1);
            assertThat(conflicts).hasSize(1);
            CreatedWorkspaceResult retry = workspaceUseCase.createWorkspace(
                    idempotencyKey,
                    CREATION_KEY,
                    command
            );
            assertThat(retry).isEqualTo(committed.getFirst());
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM teams WHERE name = ?",
                    Integer.class,
                    "동시 생성 스터디"
            )).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @DisplayName("생성 키와 복구 키 및 현재 접근 키는 생성 제한과 회전 및 분실 복구를 각각 보호한다")
    @Test
    void protectsCreationRotationAndRecoveryWithTheRightSecret() {
        CreateWorkspaceCommand command = new CreateWorkspaceCommand(
                "접근 키 정책 스터디",
                "파일럿 시즌",
                LocalDate.of(2026, 7, 21),
                LocalDate.of(2026, 8, 31),
                List.of("박민서")
        );
        String idempotencyKey = "workspace-idempotency-key-policy-001";
        String accessKeyChangeIdempotencyKey = "workspace-access-key-change-000001";

        assertThatThrownBy(() -> workspaceUseCase.createWorkspace(
                "workspace-idempotency-denied-00001",
                "wrong-operator-key",
                command
        )).isInstanceOf(WorkspaceCreationDeniedException.class);

        CreatedWorkspaceResult created = workspaceUseCase.createWorkspace(
                idempotencyKey,
                CREATION_KEY,
                command
        );
        assertThatThrownBy(() -> workspaceUseCase.rotateAccessKey(
                created.teamId(),
                created.seasonId(),
                null,
                created.accessKey()
        )).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> workspaceUseCase.recoverAccessKey(
                created.teamId(),
                created.seasonId(),
                null,
                "wrong-recovery-key"
        )).isInstanceOf(WorkspaceRecoveryDeniedException.class);
        assertThatThrownBy(() -> workspaceUseCase.recoverAccessKey(
                created.teamId(),
                created.seasonId(),
                null,
                RECOVERY_KEY
        )).isInstanceOf(DomainValidationException.class);
        WorkspaceUseCase.AccessKeyResult rotated = workspaceUseCase.rotateAccessKey(
                created.teamId(),
                created.seasonId(),
                accessKeyChangeIdempotencyKey,
                created.accessKey()
        );
        WorkspaceUseCase.AccessKeyResult rotationReplay = workspaceUseCase.rotateAccessKey(
                created.teamId(),
                created.seasonId(),
                accessKeyChangeIdempotencyKey,
                created.accessKey()
        );

        assertThat(rotated.accessKey()).matches("[A-Za-z0-9_-]{43}").isNotEqualTo(created.accessKey());
        assertThat(rotationReplay).isEqualTo(rotated);
        assertThatThrownBy(() -> workspaceUseCase.getWorkspace(
                created.teamId(), created.seasonId(), created.accessKey()))
                .isInstanceOf(WorkspaceAccessDeniedException.class);
        assertThat(workspaceUseCase.getWorkspace(
                created.teamId(), created.seasonId(), rotated.accessKey()).team().id())
                .isEqualTo(created.teamId());
        assertThatThrownBy(() -> workspaceUseCase.createWorkspace(
                idempotencyKey,
                CREATION_KEY,
                command
        )).isInstanceOf(IdempotencyReplayExpiredException.class);

        assertThatThrownBy(() -> workspaceUseCase.recoverAccessKey(
                created.teamId(),
                created.seasonId(),
                accessKeyChangeIdempotencyKey,
                "wrong-recovery-key"
        ))
                .isInstanceOf(WorkspaceRecoveryDeniedException.class);

        WorkspaceUseCase.AccessKeyResult recovered = workspaceUseCase.recoverAccessKey(
                created.teamId(), created.seasonId(), accessKeyChangeIdempotencyKey, RECOVERY_KEY);
        assertThatThrownBy(() -> workspaceUseCase.recoverAccessKey(
                created.teamId(),
                created.seasonId(),
                accessKeyChangeIdempotencyKey,
                "wrong-recovery-key"
        )).isInstanceOf(WorkspaceRecoveryDeniedException.class);
        WorkspaceUseCase.AccessKeyResult recoveryReplay = workspaceUseCase.recoverAccessKey(
                created.teamId(), created.seasonId(), accessKeyChangeIdempotencyKey, RECOVERY_KEY);
        assertThat(recovered.accessKey()).matches("[A-Za-z0-9_-]{43}").isNotEqualTo(rotated.accessKey());
        assertThat(recoveryReplay).isEqualTo(recovered);
        assertThatThrownBy(() -> workspaceUseCase.getWorkspace(
                created.teamId(), created.seasonId(), rotated.accessKey()))
                .isInstanceOf(WorkspaceAccessDeniedException.class);
        assertThat(workspaceUseCase.getWorkspace(
                created.teamId(), created.seasonId(), recovered.accessKey()).team().id())
                .isEqualTo(created.teamId());
        assertThat(jdbcTemplate.queryForMap(
                "SELECT access_key_hash, last_access_key_change_idempotency_hash "
                        + "FROM teams WHERE name = ?",
                "접근 키 정책 스터디"
        )).allSatisfy((column, value) -> assertThat(value.toString())
                .matches("[0-9a-f]{64}")
                .doesNotContain(recovered.accessKey())
                .doesNotContain(accessKeyChangeIdempotencyKey));
        assertThat(jdbcTemplate.queryForList(
                "SELECT idempotency_hash FROM access_key_change_history "
                        + "WHERE team_id = UUID_TO_BIN(?)",
                String.class,
                created.teamId().toString()
        ))
                .hasSize(2)
                .doesNotHaveDuplicates()
                .allSatisfy(hash -> assertThat(hash)
                        .matches("[0-9a-f]{64}")
                        .doesNotContain(accessKeyChangeIdempotencyKey));
    }

    @DisplayName("두 번 회전한 뒤 과거 멱등 키를 다시 사용해도 이전 접근 키가 부활하지 않는다")
    @Test
    void expiresHistoricalRotationReplayWithoutRestoringTheOldKey() {
        CreatedWorkspaceResult created = workspaceUseCase.createWorkspace(
                "workspace-idempotency-rotation-history-01",
                CREATION_KEY,
                new CreateWorkspaceCommand(
                        "회전 이력 스터디",
                        "파일럿 시즌",
                        LocalDate.of(2026, 7, 21),
                        LocalDate.of(2026, 8, 31),
                        List.of("박민서")
                )
        );
        String firstIdempotencyKey = "workspace-rotation-history-first-0001";
        String secondIdempotencyKey = "workspace-rotation-history-second-001";

        WorkspaceUseCase.AccessKeyResult first = workspaceUseCase.rotateAccessKey(
                created.teamId(),
                created.seasonId(),
                firstIdempotencyKey,
                created.accessKey()
        );
        WorkspaceUseCase.AccessKeyResult second = workspaceUseCase.rotateAccessKey(
                created.teamId(),
                created.seasonId(),
                secondIdempotencyKey,
                first.accessKey()
        );
        Map<String, Object> stateBeforeExpiredReplay = jdbcTemplate.queryForMap(
                "SELECT access_key_hash, last_access_key_change_idempotency_hash "
                        + "FROM teams WHERE id = UUID_TO_BIN(?)",
                created.teamId().toString()
        );

        assertThatThrownBy(() -> workspaceUseCase.rotateAccessKey(
                created.teamId(),
                created.seasonId(),
                firstIdempotencyKey,
                second.accessKey()
        )).isInstanceOf(IdempotencyReplayExpiredException.class);

        assertThat(jdbcTemplate.queryForMap(
                "SELECT access_key_hash, last_access_key_change_idempotency_hash "
                        + "FROM teams WHERE id = UUID_TO_BIN(?)",
                created.teamId().toString()
        )).isEqualTo(stateBeforeExpiredReplay);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM access_key_change_history WHERE team_id = UUID_TO_BIN(?)",
                Integer.class,
                created.teamId().toString()
        )).isEqualTo(2);
        assertThatThrownBy(() -> workspaceUseCase.getWorkspace(
                created.teamId(), created.seasonId(), first.accessKey()))
                .isInstanceOf(WorkspaceAccessDeniedException.class);
        assertThat(workspaceUseCase.getWorkspace(
                created.teamId(), created.seasonId(), second.accessKey()).team().id())
                .isEqualTo(created.teamId());
    }

    @DisplayName("두 번 복구한 뒤 과거 멱등 키는 만료되고 최신 접근 키만 계속 유효하다")
    @Test
    void expiresHistoricalRecoveryReplayWithoutRestoringTheOldKey() {
        CreatedWorkspaceResult created = workspaceUseCase.createWorkspace(
                "workspace-idempotency-recovery-history-01",
                CREATION_KEY,
                new CreateWorkspaceCommand(
                        "복구 이력 스터디",
                        "파일럿 시즌",
                        LocalDate.of(2026, 7, 21),
                        LocalDate.of(2026, 8, 31),
                        List.of("박민서")
                )
        );
        String firstIdempotencyKey = "workspace-recovery-history-first-0001";
        String secondIdempotencyKey = "workspace-recovery-history-second-001";

        WorkspaceUseCase.AccessKeyResult first = workspaceUseCase.recoverAccessKey(
                created.teamId(), created.seasonId(), firstIdempotencyKey, RECOVERY_KEY);
        WorkspaceUseCase.AccessKeyResult second = workspaceUseCase.recoverAccessKey(
                created.teamId(), created.seasonId(), secondIdempotencyKey, RECOVERY_KEY);
        Map<String, Object> stateBeforeExpiredReplay = jdbcTemplate.queryForMap(
                "SELECT access_key_hash, last_access_key_change_idempotency_hash "
                        + "FROM teams WHERE id = UUID_TO_BIN(?)",
                created.teamId().toString()
        );

        assertThatThrownBy(() -> workspaceUseCase.recoverAccessKey(
                created.teamId(), created.seasonId(), firstIdempotencyKey, "wrong-recovery-key"))
                .isInstanceOf(WorkspaceRecoveryDeniedException.class);
        assertThatThrownBy(() -> workspaceUseCase.recoverAccessKey(
                created.teamId(), created.seasonId(), firstIdempotencyKey, RECOVERY_KEY))
                .isInstanceOf(IdempotencyReplayExpiredException.class);

        assertThat(jdbcTemplate.queryForMap(
                "SELECT access_key_hash, last_access_key_change_idempotency_hash "
                        + "FROM teams WHERE id = UUID_TO_BIN(?)",
                created.teamId().toString()
        )).isEqualTo(stateBeforeExpiredReplay);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM access_key_change_history WHERE team_id = UUID_TO_BIN(?)",
                Integer.class,
                created.teamId().toString()
        )).isEqualTo(2);
        assertThatThrownBy(() -> workspaceUseCase.getWorkspace(
                created.teamId(), created.seasonId(), first.accessKey()))
                .isInstanceOf(WorkspaceAccessDeniedException.class);
        assertThat(workspaceUseCase.getWorkspace(
                created.teamId(), created.seasonId(), second.accessKey()).team().id())
                .isEqualTo(created.teamId());
    }

    @DisplayName("멱등 키가 없거나 짧거나 URL 안전 형식과 길이 제한을 벗어나면 생성을 거절한다")
    @Test
    void rejectsMalformedIdempotencyKeys() {
        CreateWorkspaceCommand command = new CreateWorkspaceCommand(
                "멱등 키 검증 스터디",
                "파일럿 시즌",
                LocalDate.of(2026, 7, 21),
                LocalDate.of(2026, 8, 31),
                List.of("박민서")
        );
        String[] malformedKeys = {
                null,
                "",
                " ".repeat(32),
                "too-short",
                "contains/slash-but-still-not-valid-000001",
                "a".repeat(201)
        };

        for (String malformedKey : malformedKeys) {
            assertThatThrownBy(() -> workspaceUseCase.createWorkspace(
                    malformedKey,
                    CREATION_KEY,
                    command
            )).isInstanceOfSatisfying(
                    DomainValidationException.class,
                    exception -> assertThat(exception.getMessage()).contains("멱등 키")
            );
        }
    }

    @DisplayName("운영 비밀 키가 설정되지 않으면 로컬 생성은 열고 접근 키 복구는 거절한다")
    @Test
    void opensLocalCreationButDeniesRecoveryWhenOperatorKeyIsNotConfigured() {
        WorkspaceService service = new WorkspaceService(
                mock(WorkspaceRepository.class),
                Clock.systemUTC(),
                "",
                ""
        );

        CreatedWorkspaceResult created = service.createWorkspace(
                "workspace-idempotency-local-open-0001",
                null,
                new CreateWorkspaceCommand(
                        "로컬 스터디",
                        "로컬 시즌",
                        LocalDate.of(2026, 7, 21),
                        LocalDate.of(2026, 8, 31),
                        List.of("박민서")
                )
        );

        assertThat(created.accessKey()).matches("[A-Za-z0-9_-]{43}");

        assertThatThrownBy(() -> service.recoverAccessKey(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "workspace-access-key-recover-local-001",
                "any-presented-key"
        )).isInstanceOf(WorkspaceRecoveryDeniedException.class);
    }

    @DisplayName("같은 버전의 팀을 읽은 두 트랜잭션은 접근 키 변경을 모두 커밋할 수 없다")
    @Test
    void rejectsStaleConcurrentAccessKeyUpdate() {
        CreatedWorkspaceResult created = workspaceUseCase.createWorkspace(
                "workspace-idempotency-optimistic-lock-01",
                CREATION_KEY,
                new CreateWorkspaceCommand(
                        "동시 회전 스터디",
                        "파일럿 시즌",
                        LocalDate.of(2026, 7, 21),
                        LocalDate.of(2026, 8, 31),
                        List.of("박민서")
                )
        );
        EntityManager firstEntityManager = entityManagerFactory.createEntityManager();
        EntityManager secondEntityManager = entityManagerFactory.createEntityManager();

        try {
            Team first = firstEntityManager.find(Team.class, created.teamId());
            Team stale = secondEntityManager.find(Team.class, created.teamId());
            firstEntityManager.detach(first);
            secondEntityManager.detach(stale);

            first.changeAccessKey("1".repeat(64), "a".repeat(64));
            stale.changeAccessKey("2".repeat(64), "b".repeat(64));
            workspaceRepository.saveTeam(first);

            assertThatThrownBy(() -> workspaceRepository.saveTeam(stale))
                    .isInstanceOf(WorkspaceAccessKeyConflictException.class);
        } finally {
            firstEntityManager.close();
            secondEntityManager.close();
        }
    }

    private Object runCreationTransaction(
            TransactionTemplate transactionTemplate,
            WorkspaceService service,
            String idempotencyKey,
            CreateWorkspaceCommand command
    ) {
        try {
            return transactionTemplate.execute(status -> service.createWorkspace(
                    idempotencyKey,
                    CREATION_KEY,
                    command
            ));
        } catch (IdempotencyKeyConflictException exception) {
            return exception;
        }
    }

    private Object runRoutineCreationTransaction(
            TransactionTemplate transactionTemplate,
            WorkspaceService service,
            CreatedWorkspaceResult workspace,
            String idempotencyKey,
            CreateRoutineCommand command
    ) {
        try {
            return transactionTemplate.execute(status -> service.createRoutine(
                    workspace.teamId(),
                    workspace.seasonId(),
                    idempotencyKey,
                    workspace.accessKey(),
                    command
            ));
        } catch (IdempotencyKeyConflictException exception) {
            return exception;
        }
    }

    private int contentReservationCount(UUID teamId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM content_creation_idempotency WHERE team_id = UUID_TO_BIN(?)",
                Integer.class,
                teamId.toString()
        );
    }

    private MemberResult memberNamed(WorkspaceResult workspace, String name) {
        return workspace.members().stream()
                .filter(member -> member.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private String contentIdempotencyKey(String suffix) {
        return "content-idempotency-" + suffix + "-0000000000000000";
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        }
    }
}
