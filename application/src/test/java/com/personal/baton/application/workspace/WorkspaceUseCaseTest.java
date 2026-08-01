package com.personal.baton.application.workspace;

import com.personal.baton.BatonApplication;
import com.personal.baton.application.identity.error.IdentityNotFoundException;
import com.personal.baton.application.identity.port.in.MemberIdentityUseCase.AuthenticatedAccount;
import com.personal.baton.application.workspace.error.IdempotencyKeyConflictException;
import com.personal.baton.application.workspace.error.IdempotencyKeyReusedException;
import com.personal.baton.application.workspace.error.IdempotencyReplayExpiredException;
import com.personal.baton.application.workspace.error.MemberNameConflictException;
import com.personal.baton.application.workspace.error.RoleNameConflictException;
import com.personal.baton.application.workspace.error.RoleHandoffStateConflictException;
import com.personal.baton.application.workspace.error.RoleHandoffWarningConfirmationRequiredException;
import com.personal.baton.application.workspace.error.SeasonEndedException;
import com.personal.baton.application.workspace.error.SeasonRoundNameConflictException;
import com.personal.baton.application.workspace.error.SeasonSuccessorExistsException;
import com.personal.baton.application.workspace.error.WorkspaceAccessDeniedException;
import com.personal.baton.application.workspace.error.WorkspaceAccessKeyConflictException;
import com.personal.baton.application.workspace.error.WorkspaceContentConflictException;
import com.personal.baton.application.workspace.error.WorkspaceCreationDeniedException;
import com.personal.baton.application.workspace.error.WorkspaceNotFoundException;
import com.personal.baton.application.workspace.error.WorkspaceRecoveryDeniedException;
import com.personal.baton.application.workspace.port.in.ContinuitySignalType;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase;
import com.personal.baton.application.workspace.port.in.WorkspaceAuthorization.SessionAccount;
import com.personal.baton.application.workspace.port.in.WorkspaceDecisionUseCase.CreateDecisionCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceHandoffItemUseCase.CreateHandoffItemCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceMemberUseCase.CreateMemberCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceRoleUseCase.CreateRoleCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceRoleResourceUseCase.CreateRoleResourceCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceRoutineUseCase.CreateRoutineCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceSeasonRoundUseCase.CreateSeasonRoundCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceCreationUseCase.CreateWorkspaceCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceCreationUseCase.CreatedWorkspaceResult;
import com.personal.baton.application.workspace.port.in.WorkspaceDecisionUseCase.DecisionResult;
import com.personal.baton.application.workspace.port.in.WorkspaceHandoffItemUseCase.HandoffItemResult;
import com.personal.baton.application.workspace.port.in.WorkspaceMemberUseCase.MemberResult;
import com.personal.baton.application.workspace.port.in.WorkspaceRoleUseCase.RoleResult;
import com.personal.baton.application.workspace.port.in.WorkspaceRoleHandoffUseCase.RoleHandoffTransitionResult;
import com.personal.baton.application.workspace.port.in.WorkspaceRoleResourceQueryUseCase.RoleResourceResult;
import com.personal.baton.application.workspace.port.in.WorkspaceSeasonRoundUseCase.RoutineExecutionResult;
import com.personal.baton.application.workspace.port.in.WorkspaceRoutineUseCase.RoutineResult;
import com.personal.baton.application.workspace.port.in.WorkspaceSeasonRoundUseCase.SeasonRoundResult;
import com.personal.baton.application.workspace.port.in.WorkspaceMemberUseCase.UpdateMemberCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceRoleUseCase.UpdateRoleCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceRoleResourceUseCase.UpdateRoleResourceCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceRoutineUseCase.UpdateRoutineCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceSeasonRoundUseCase.UpdateSeasonRoundCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceDecisionUseCase.UpdateDecisionCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceHandoffItemUseCase.UpdateHandoffItemCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceQueryUseCase.WorkspaceResult;
import com.personal.baton.application.workspace.port.out.WorkspaceRepository;
import com.personal.baton.domain.workspace.Decision;
import com.personal.baton.domain.workspace.DomainValidationException;
import com.personal.baton.domain.workspace.HandoffCategory;
import com.personal.baton.domain.workspace.HandoffItem;
import com.personal.baton.domain.workspace.Member;
import com.personal.baton.domain.workspace.RoutinePhase;
import com.personal.baton.domain.workspace.RoleResource;
import com.personal.baton.domain.workspace.RoleHandoff;
import com.personal.baton.domain.workspace.RoleHandoffStatus;
import com.personal.baton.domain.workspace.RoutineExecution;
import com.personal.baton.domain.workspace.RoutineStatus;
import com.personal.baton.domain.workspace.Season;
import com.personal.baton.domain.workspace.SeasonRound;
import com.personal.baton.domain.workspace.Team;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
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
import static org.mockito.ArgumentMatchers.anyList;
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

    @Autowired
    private ApplicationContext applicationContext;

    @DisplayName("애플리케이션은 임시 fallback 사용자 계정을 구성하지 않는다")
    @Test
    void doesNotConfigureFallbackUserAccount() throws ClassNotFoundException {
        Class<?> userDetailsServiceType = Class.forName(
                "org.springframework.security.core.userdetails.UserDetailsService"
        );

        assertThat(applicationContext.getBeanNamesForType(userDetailsServiceType)).isEmpty();
    }

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
        SeasonRoundResult firstRound = workspaceUseCase.createSeasonRound(
                created.teamId(),
                created.seasonId(),
                contentIdempotencyKey("round-first-workspace-flow"),
                created.accessKey(),
                new CreateSeasonRoundCommand("1회차", LocalDate.of(2026, 7, 23))
        );
        RoutineExecutionResult routineExecution = firstRound.routineExecutions().getFirst();
        assertThat(routineExecution.routineId()).isEqualTo(routine.id());
        RoutineExecutionResult completedExecution = workspaceUseCase.updateRoutineExecutionCompletion(
                created.teamId(),
                created.seasonId(),
                firstRound.id(),
                routineExecution.id(),
                created.accessKey(),
                true
        );
        assertThat(completedExecution.status()).isEqualTo(RoutineStatus.DONE);

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
        assertThat(handoffItem.createdAt()).isEqualTo(FIXED_INSTANT);
        assertThat(completedItem.createdAt()).isEqualTo(FIXED_INSTANT);
        assertThat(completedItem.completed()).isTrue();

        RoleResourceResult resource = workspaceUseCase.createRoleResource(
                created.teamId(),
                created.seasonId(),
                contentIdempotencyKey("resource-question-guide"),
                created.accessKey(),
                new CreateRoleResourceCommand(
                        role.id(),
                        "질문 정리 가이드",
                        "https://docs.example.com/question-guide",
                        "질문을 모으고 분류하는 기준"
                )
        );
        RoleResourceResult updatedResource = workspaceUseCase.updateRoleResource(
                created.teamId(),
                created.seasonId(),
                resource.id(),
                created.accessKey(),
                new UpdateRoleResourceCommand(
                        recorderRole.id(),
                        "질문 정리 가이드 개정판",
                        "https://docs.example.com/question-guide-v2",
                        "이번 시즌에 맞춘 질문 분류 기준"
                )
        );
        assertThat(updatedResource.roleId()).isEqualTo(recorderRole.id());
        assertThat(resource.createdAt()).isEqualTo(FIXED_INSTANT);
        assertThat(updatedResource.createdAt()).isEqualTo(FIXED_INSTANT);
        RoleResourceResult replayedResource = workspaceUseCase.createRoleResource(
                created.teamId(),
                created.seasonId(),
                contentIdempotencyKey("resource-question-guide"),
                created.accessKey(),
                new CreateRoleResourceCommand(
                        role.id(),
                        "질문 정리 가이드",
                        "https://docs.example.com/question-guide",
                        "질문을 모으고 분류하는 기준"
                )
        );
        assertThat(replayedResource).isEqualTo(updatedResource);

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

        assertThatThrownBy(() -> workspaceUseCase.updateRoutineExecutionCompletion(
                created.teamId(),
                created.seasonId(),
                firstRound.id(),
                UUID.randomUUID(),
                created.accessKey(),
                true
        ))
                .isInstanceOfSatisfying(WorkspaceNotFoundException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("ROUTINE_EXECUTION_NOT_FOUND"));

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

        assertThat(statistics.getPrepareStatementCount()).isEqualTo(12);
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
                assertThat(savedRoutine.title()).isEqualTo("모임 전 질문 모으기"));
        assertThat(reloaded.rounds()).singleElement().satisfies(savedRound -> {
            assertThat(savedRound.id()).isEqualTo(firstRound.id());
            assertThat(savedRound.meetingDate()).isEqualTo(LocalDate.of(2026, 7, 23));
            assertThat(savedRound.routineExecutions()).singleElement().satisfies(savedExecution -> {
                assertThat(savedExecution.routineId()).isEqualTo(routine.id());
                assertThat(savedExecution.status()).isEqualTo(RoutineStatus.DONE);
            });
        });
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
        assertThat(reloaded.resources()).singleElement().satisfies(savedResource -> {
            assertThat(savedResource.id()).isEqualTo(resource.id());
            assertThat(savedResource.roleId()).isEqualTo(recorderRole.id());
            assertThat(savedResource.title()).isEqualTo("질문 정리 가이드 개정판");
            assertThat(savedResource.url()).isEqualTo("https://docs.example.com/question-guide-v2");
            assertThat(savedResource.description()).isEqualTo("이번 시즌에 맞춘 질문 분류 기준");
        });
        assertThat(reloaded.continuitySignals())
                .singleElement()
                .satisfies(signal -> {
                    assertThat(signal.type())
                            .isEqualTo(ContinuitySignalType.ROLE_PREPARATION_INCOMPLETE);
                    assertThat(signal.roleId()).isEqualTo(role.id());
                    assertThat(signal.reason()).contains("역할 자료");
                    assertThat(signal.recommendedAction()).isNotBlank();
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
        assertThatThrownBy(() -> workspaceUseCase.createRoleResource(
                otherWorkspace.teamId(),
                otherWorkspace.seasonId(),
                contentIdempotencyKey("resource-cross-team-role"),
                otherWorkspace.accessKey(),
                new CreateRoleResourceCommand(
                        role.id(),
                        "교차 팀 자료",
                        "https://docs.example.com/cross-team",
                        null
                )
        )).isInstanceOfSatisfying(WorkspaceNotFoundException.class,
                exception -> assertThat(exception.getCode()).isEqualTo("ROLE_NOT_FOUND"));
        assertThatThrownBy(() -> workspaceUseCase.updateRoleResource(
                otherWorkspace.teamId(),
                otherWorkspace.seasonId(),
                resource.id(),
                otherWorkspace.accessKey(),
                new UpdateRoleResourceCommand(
                        UUID.randomUUID(),
                        "교차 팀 자료 수정",
                        "https://docs.example.com/cross-team-update",
                        null
                )
        )).isInstanceOfSatisfying(WorkspaceNotFoundException.class,
                exception -> assertThat(exception.getCode()).isEqualTo("ROLE_RESOURCE_NOT_FOUND"));
    }

    @DisplayName("역할 바통은 경고 확인 뒤 전달하고 다음 담당자의 수락으로 담당 기간을 원자적으로 바꾼다")
    @Test
    void transfersAcceptsAndCancelsRoleHandoffs() {
        CreatedWorkspaceResult created = workspaceUseCase.createWorkspace(
                "workspace-role-handoff-lifecycle-01",
                CREATION_KEY,
                new CreateWorkspaceCommand(
                        "바통 실사용 스터디",
                        "파일럿 시즌",
                        LocalDate.of(2026, 7, 1),
                        LocalDate.of(2026, 9, 30),
                        List.of("박민서", "김준호")
                )
        );
        WorkspaceResult workspace = workspaceUseCase.getWorkspace(
                created.teamId(),
                created.seasonId(),
                created.accessKey()
        );
        MemberResult minseo = memberNamed(workspace, "박민서");
        MemberResult junho = memberNamed(workspace, "김준호");
        RoleResult role = workspaceUseCase.createRole(
                created.teamId(),
                created.seasonId(),
                contentIdempotencyKey("handoff-lifecycle-role"),
                created.accessKey(),
                new CreateRoleCommand(
                        "진행자",
                        "매주 모임을 진행합니다",
                        minseo.id(),
                        null,
                        LocalDate.of(2026, 7, 1),
                        LocalDate.of(2026, 7, 31),
                        List.of("안건 확인", "시간 관리"),
                        "진행 문서 권한을 함께 넘겨야 합니다"
                )
        );
        HandoffItemResult item = workspaceUseCase.createHandoffItem(
                created.teamId(),
                created.seasonId(),
                contentIdempotencyKey("handoff-lifecycle-item"),
                created.accessKey(),
                new CreateHandoffItemCommand(
                        role.id(),
                        "진행 문서 권한 넘기기",
                        HandoffCategory.RESOURCE
                )
        );

        String prepareKey = contentIdempotencyKey("handoff-lifecycle-prepare");
        RoleHandoffTransitionResult prepared = workspaceUseCase.prepareRoleHandoff(
                created.teamId(),
                created.seasonId(),
                role.id(),
                prepareKey,
                created.accessKey(),
                new WorkspaceUseCase.PrepareRoleHandoffCommand(
                        junho.id(),
                        LocalDate.of(2026, 8, 1),
                        LocalDate.of(2026, 9, 30)
                )
        );

        assertThat(prepared.handoff().status()).isEqualTo(RoleHandoffStatus.PREPARING);
        assertThat(prepared.handoff().preparedAt()).isEqualTo(FIXED_INSTANT);
        assertThat(prepared.role().nextMemberId()).isEqualTo(junho.id());
        assertThat(workspaceUseCase.prepareRoleHandoff(
                created.teamId(),
                created.seasonId(),
                role.id(),
                prepareKey,
                created.accessKey(),
                new WorkspaceUseCase.PrepareRoleHandoffCommand(
                        junho.id(),
                        LocalDate.of(2026, 8, 1),
                        LocalDate.of(2026, 9, 30)
                )
        ).handoff().id()).isEqualTo(prepared.handoff().id());

        assertThatThrownBy(() -> workspaceUseCase.transferRoleHandoff(
                created.teamId(),
                created.seasonId(),
                role.id(),
                prepared.handoff().id(),
                created.accessKey(),
                new WorkspaceUseCase.TransferRoleHandoffCommand(minseo.id(), false)
        )).isInstanceOf(RoleHandoffWarningConfirmationRequiredException.class);

        RoleHandoffTransitionResult transferred = workspaceUseCase.transferRoleHandoff(
                created.teamId(),
                created.seasonId(),
                role.id(),
                prepared.handoff().id(),
                created.accessKey(),
                new WorkspaceUseCase.TransferRoleHandoffCommand(minseo.id(), true)
        );

        assertThat(transferred.handoff().status()).isEqualTo(RoleHandoffStatus.TRANSFERRED);
        assertThat(transferred.handoff().activeItemCount()).isEqualTo(1);
        assertThat(transferred.handoff().incompleteItemCount()).isEqualTo(1);
        assertThat(transferred.handoff().resourceCount()).isZero();
        assertThat(transferred.handoff().warningAcknowledged()).isTrue();
        assertThatThrownBy(() -> workspaceUseCase.updateRole(
                created.teamId(),
                created.seasonId(),
                role.id(),
                created.accessKey(),
                new UpdateRoleCommand(
                        "진행자",
                        "전달 뒤 바뀌면 안 되는 역할 설명",
                        minseo.id(),
                        junho.id(),
                        LocalDate.of(2026, 7, 1),
                        LocalDate.of(2026, 7, 31),
                        List.of("안건 확인", "시간 관리"),
                        "진행 문서 권한을 함께 넘겨야 합니다"
                )
        )).isInstanceOf(RoleHandoffStateConflictException.class);
        assertThatThrownBy(() -> workspaceUseCase.updateHandoffItemCompletion(
                created.teamId(),
                created.seasonId(),
                item.id(),
                created.accessKey(),
                true
        )).isInstanceOf(RoleHandoffStateConflictException.class);
        assertThatThrownBy(() -> workspaceUseCase.updateSeasonEnding(
                created.teamId(),
                created.seasonId(),
                created.accessKey(),
                true
        )).isInstanceOf(RoleHandoffStateConflictException.class);
        assertThatThrownBy(() -> workspaceUseCase.acceptRoleHandoff(
                created.teamId(),
                created.seasonId(),
                role.id(),
                prepared.handoff().id(),
                created.accessKey(),
                new WorkspaceUseCase.ConfirmRoleHandoffCommand(minseo.id())
        )).isInstanceOf(RoleHandoffStateConflictException.class);

        RoleHandoffTransitionResult accepted = workspaceUseCase.acceptRoleHandoff(
                created.teamId(),
                created.seasonId(),
                role.id(),
                prepared.handoff().id(),
                created.accessKey(),
                new WorkspaceUseCase.ConfirmRoleHandoffCommand(junho.id())
        );

        assertThat(accepted.handoff().status()).isEqualTo(RoleHandoffStatus.ACCEPTED);
        assertThat(accepted.handoff().acceptedAt()).isEqualTo(FIXED_INSTANT);
        assertThat(accepted.role().currentMemberId()).isEqualTo(junho.id());
        assertThat(accepted.role().nextMemberId()).isNull();
        assertThat(accepted.role().assignmentStartDate()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(accepted.role().assignmentEndDate()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(accepted.handoff().outgoingAssignmentStartDate())
                .isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(accepted.handoff().outgoingAssignmentEndDate())
                .isEqualTo(LocalDate.of(2026, 7, 31));

        RoleHandoffTransitionResult secondPrepared = workspaceUseCase.prepareRoleHandoff(
                created.teamId(),
                created.seasonId(),
                role.id(),
                contentIdempotencyKey("handoff-lifecycle-second-prepare"),
                created.accessKey(),
                new WorkspaceUseCase.PrepareRoleHandoffCommand(
                        minseo.id(),
                        LocalDate.of(2026, 9, 1),
                        LocalDate.of(2026, 9, 30)
                )
        );
        RoleHandoffTransitionResult cancelled = workspaceUseCase.cancelRoleHandoff(
                created.teamId(),
                created.seasonId(),
                role.id(),
                secondPrepared.handoff().id(),
                created.accessKey(),
                new WorkspaceUseCase.ConfirmRoleHandoffCommand(junho.id())
        );

        assertThat(cancelled.handoff().status()).isEqualTo(RoleHandoffStatus.CANCELLED);
        assertThat(cancelled.handoff().cancelledByMemberId()).isEqualTo(junho.id());
        assertThat(cancelled.role().nextMemberId()).isNull();
        WorkspaceResult reloaded = workspaceUseCase.getWorkspace(
                created.teamId(),
                created.seasonId(),
                created.accessKey()
        );
        assertThat(reloaded.roleHandoffs())
                .extracting(WorkspaceUseCase.RoleHandoffResult::status)
                .containsExactlyInAnyOrder(
                        RoleHandoffStatus.ACCEPTED,
                        RoleHandoffStatus.CANCELLED
                );
    }

    @DisplayName("바통 항목 수정은 전달 커밋 뒤 최신 동결 상태를 다시 확인한다")
    @Test
    void rejectsHandoffItemUpdateAfterConcurrentTransfer() throws Exception {
        CreatedWorkspaceResult created = workspaceUseCase.createWorkspace(
                "workspace-role-handoff-freeze-lock-01",
                CREATION_KEY,
                new CreateWorkspaceCommand(
                        "바통 동결 직렬화 스터디",
                        "파일럿 시즌",
                        LocalDate.of(2026, 7, 1),
                        LocalDate.of(2026, 9, 30),
                        List.of("박민서", "김준호")
                )
        );
        WorkspaceResult workspace = workspaceUseCase.getWorkspace(
                created.teamId(),
                created.seasonId(),
                created.accessKey()
        );
        MemberResult minseo = memberNamed(workspace, "박민서");
        MemberResult junho = memberNamed(workspace, "김준호");
        RoleResult role = workspaceUseCase.createRole(
                created.teamId(),
                created.seasonId(),
                contentIdempotencyKey("handoff-freeze-lock-role"),
                created.accessKey(),
                new CreateRoleCommand(
                        "진행자",
                        "매주 모임을 진행합니다",
                        minseo.id(),
                        junho.id(),
                        LocalDate.of(2026, 7, 1),
                        LocalDate.of(2026, 7, 31),
                        List.of("안건 확인"),
                        null
                )
        );
        HandoffItemResult item = workspaceUseCase.createHandoffItem(
                created.teamId(),
                created.seasonId(),
                contentIdempotencyKey("handoff-freeze-lock-item"),
                created.accessKey(),
                new CreateHandoffItemCommand(
                        role.id(),
                        "진행 문서 권한 넘기기",
                        HandoffCategory.RESOURCE
                )
        );
        RoleHandoffTransitionResult prepared = workspaceUseCase.prepareRoleHandoff(
                created.teamId(),
                created.seasonId(),
                role.id(),
                contentIdempotencyKey("handoff-freeze-lock-prepare"),
                created.accessKey(),
                new WorkspaceUseCase.PrepareRoleHandoffCommand(
                        junho.id(),
                        LocalDate.of(2026, 8, 1),
                        LocalDate.of(2026, 9, 30)
                )
        );

        CountDownLatch itemReachedRoleLock = new CountDownLatch(1);
        CountDownLatch allowItemRoleLock = new CountDownLatch(1);
        CountDownLatch itemRoleLockRequested = new CountDownLatch(1);
        CountDownLatch transferFlushed = new CountDownLatch(1);
        CountDownLatch allowTransferCommit = new CountDownLatch(1);
        WorkspaceRepository coordinatedRepository = mock(
                WorkspaceRepository.class,
                delegatesTo(workspaceRepository)
        );
        doAnswer(invocation -> {
            itemReachedRoleLock.countDown();
            if (!allowItemRoleLock.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("바통 항목의 역할 잠금 대기 시간이 초과됐습니다");
            }
            itemRoleLockRequested.countDown();
            return workspaceRepository.findRolesByTeamIdAndSeasonIdAndIdsWithSharedLock(
                    invocation.getArgument(0),
                    invocation.getArgument(1),
                    invocation.getArgument(2)
            );
        }).when(coordinatedRepository).findRolesByTeamIdAndSeasonIdAndIdsWithSharedLock(
                any(UUID.class),
                any(UUID.class),
                anyList()
        );
        doAnswer(invocation -> {
            RoleHandoff saved = workspaceRepository.saveRoleHandoff(invocation.getArgument(0));
            transferFlushed.countDown();
            if (!allowTransferCommit.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("역할 바통 전달 transaction 해제 대기 시간이 초과됐습니다");
            }
            return saved;
        }).when(coordinatedRepository).saveRoleHandoff(any(RoleHandoff.class));
        WorkspaceService coordinatedService = new WorkspaceService(
                coordinatedRepository,
                Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC),
                CREATION_KEY,
                RECOVERY_KEY
        );
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Object> itemUpdate = executor.submit(() -> {
                try {
                    return transactionTemplate.execute(status ->
                            coordinatedService.updateHandoffItem(
                                    created.teamId(),
                                    created.seasonId(),
                                    item.id(),
                                    created.accessKey(),
                                    new UpdateHandoffItemCommand(
                                            role.id(),
                                            "전달 뒤 저장되면 안 되는 수정",
                                            HandoffCategory.RESOURCE
                                    )
                            ));
                } catch (RoleHandoffStateConflictException exception) {
                    return exception;
                }
            });
            assertThat(itemReachedRoleLock.await(10, TimeUnit.SECONDS)).isTrue();

            Future<RoleHandoffTransitionResult> transfer = executor.submit(() ->
                    transactionTemplate.execute(status ->
                            coordinatedService.transferRoleHandoff(
                                    created.teamId(),
                                    created.seasonId(),
                                    role.id(),
                                    prepared.handoff().id(),
                                    created.accessKey(),
                                    new WorkspaceUseCase.TransferRoleHandoffCommand(
                                            minseo.id(),
                                            true
                                    )
                            )));
            assertThat(transferFlushed.await(10, TimeUnit.SECONDS)).isTrue();

            allowItemRoleLock.countDown();
            assertThat(itemRoleLockRequested.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> itemUpdate.get(300, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            allowTransferCommit.countDown();

            assertThat(transfer.get(10, TimeUnit.SECONDS).handoff().status())
                    .isEqualTo(RoleHandoffStatus.TRANSFERRED);
            assertThat(itemUpdate.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(RoleHandoffStateConflictException.class);
            assertThat(workspaceUseCase.getWorkspace(
                    created.teamId(),
                    created.seasonId(),
                    created.accessKey()
            ).handoffItems())
                    .filteredOn(existing -> existing.id().equals(item.id()))
                    .singleElement()
                    .satisfies(existing -> assertThat(existing.label())
                            .isEqualTo("진행 문서 권한 넘기기"));
        } finally {
            allowItemRoleLock.countDown();
            allowTransferCommit.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @DisplayName("구성원 이름과 활성 상태를 바꿔도 기존 역할·결정·바통 참조와 생성 재생을 보존한다")
    @Test
    void updatesMemberLifecycleWithoutBreakingExistingReferences() {
        CreatedWorkspaceResult created = workspaceUseCase.createWorkspace(
                "workspace-member-lifecycle-reference-01",
                CREATION_KEY,
                new CreateWorkspaceCommand(
                        "구성원 생명주기 스터디",
                        "파일럿 시즌",
                        LocalDate.of(2026, 7, 21),
                        LocalDate.of(2026, 8, 31),
                        List.of("박민서")
                )
        );
        String memberIdempotencyKey = contentIdempotencyKey("member-lifecycle-reference");
        MemberResult member = workspaceUseCase.createMember(
                created.teamId(),
                created.seasonId(),
                memberIdempotencyKey,
                created.accessKey(),
                new CreateMemberCommand("최유진")
        );
        RoleResult role = workspaceUseCase.createRole(
                created.teamId(),
                created.seasonId(),
                contentIdempotencyKey("member-lifecycle-role"),
                created.accessKey(),
                new CreateRoleCommand(
                        "진행자",
                        "모임을 진행합니다",
                        member.id(),
                        null,
                        null,
                        null,
                        List.of("시간 확인"),
                        null
                )
        );
        DecisionResult decision = workspaceUseCase.createDecision(
                created.teamId(),
                created.seasonId(),
                contentIdempotencyKey("member-lifecycle-decision"),
                created.accessKey(),
                new CreateDecisionCommand(
                        "진행 순서를 고정한다",
                        "준비 시간을 줄입니다",
                        "",
                        member.id(),
                        List.of(role.id())
                )
        );
        HandoffItemResult handoffItem = workspaceUseCase.createHandoffItem(
                created.teamId(),
                created.seasonId(),
                contentIdempotencyKey("member-lifecycle-handoff"),
                created.accessKey(),
                new CreateHandoffItemCommand(
                        role.id(),
                        "진행 문서 전달",
                        HandoffCategory.RESOURCE
                )
        );

        MemberResult renamed = workspaceUseCase.updateMember(
                created.teamId(),
                created.seasonId(),
                member.id(),
                created.accessKey(),
                new UpdateMemberCommand("  최유진(진행)  ")
        );
        MemberResult deactivated = workspaceUseCase.updateMemberDeactivation(
                created.teamId(),
                created.seasonId(),
                member.id(),
                created.accessKey(),
                true
        );
        MemberResult replayed = workspaceUseCase.createMember(
                created.teamId(),
                created.seasonId(),
                memberIdempotencyKey,
                created.accessKey(),
                new CreateMemberCommand("최유진")
        );

        assertThat(renamed.name()).isEqualTo("최유진(진행)");
        assertThat(renamed.deactivatedAt()).isNull();
        assertThat(deactivated.deactivatedAt()).isEqualTo(FIXED_INSTANT);
        assertThat(replayed).isEqualTo(deactivated);
        assertThatThrownBy(() -> workspaceUseCase.updateMember(
                created.teamId(),
                created.seasonId(),
                workspaceUseCase.getWorkspace(
                        created.teamId(),
                        created.seasonId(),
                        created.accessKey()
                ).members().stream()
                        .filter(existing -> existing.name().equals("박민서"))
                        .findFirst()
                        .orElseThrow()
                        .id(),
                created.accessKey(),
                new UpdateMemberCommand("최유진(진행)")
        )).isInstanceOf(MemberNameConflictException.class);

        WorkspaceResult reloaded = workspaceUseCase.getWorkspace(
                created.teamId(),
                created.seasonId(),
                created.accessKey()
        );
        assertThat(reloaded.members())
                .filteredOn(existing -> existing.id().equals(member.id()))
                .singleElement()
                .satisfies(existing -> {
                    assertThat(existing.name()).isEqualTo("최유진(진행)");
                    assertThat(existing.deactivatedAt()).isEqualTo(FIXED_INSTANT);
                });
        assertThat(reloaded.roles())
                .filteredOn(existing -> existing.id().equals(role.id()))
                .singleElement()
                .satisfies(existing -> assertThat(existing.currentMemberId()).isEqualTo(member.id()));
        assertThat(reloaded.decisions())
                .filteredOn(existing -> existing.id().equals(decision.id()))
                .singleElement()
                .satisfies(existing -> {
                    assertThat(existing.authorMemberId()).isEqualTo(member.id());
                    assertThat(existing.authorName()).isEqualTo("최유진(진행)");
                });
        assertThat(reloaded.handoffItems())
                .filteredOn(existing -> existing.id().equals(handoffItem.id()))
                .singleElement()
                .satisfies(existing -> assertThat(existing.roleId()).isEqualTo(role.id()));
    }

    @DisplayName("비활성 구성원은 새 담당자와 작성자로 거절하고 기존 동일 참조의 수정과 복귀는 허용한다")
    @Test
    void rejectsInactiveMemberForNewReferencesButPreservesExistingOnes() {
        CreatedWorkspaceResult created = workspaceUseCase.createWorkspace(
                "workspace-inactive-member-reference-001",
                CREATION_KEY,
                new CreateWorkspaceCommand(
                        "비활성 구성원 스터디",
                        "파일럿 시즌",
                        LocalDate.of(2026, 7, 21),
                        LocalDate.of(2026, 8, 31),
                        List.of("박민서", "김준호")
                )
        );
        WorkspaceResult initial = workspaceUseCase.getWorkspace(
                created.teamId(),
                created.seasonId(),
                created.accessKey()
        );
        MemberResult minseo = memberNamed(initial, "박민서");
        MemberResult junho = memberNamed(initial, "김준호");
        RoleResult role = workspaceUseCase.createRole(
                created.teamId(),
                created.seasonId(),
                contentIdempotencyKey("inactive-member-role"),
                created.accessKey(),
                new CreateRoleCommand(
                        "진행자",
                        "모임을 진행합니다",
                        minseo.id(),
                        junho.id(),
                        null,
                        null,
                        List.of("시간 확인"),
                        null
                )
        );
        DecisionResult decision = workspaceUseCase.createDecision(
                created.teamId(),
                created.seasonId(),
                contentIdempotencyKey("inactive-member-decision"),
                created.accessKey(),
                new CreateDecisionCommand(
                        "진행 순서를 고정한다",
                        "준비 시간을 줄입니다",
                        "",
                        minseo.id(),
                        List.of(role.id())
                )
        );
        workspaceUseCase.updateMemberDeactivation(
                created.teamId(),
                created.seasonId(),
                minseo.id(),
                created.accessKey(),
                true
        );

        RoleResult updatedRole = workspaceUseCase.updateRole(
                created.teamId(),
                created.seasonId(),
                role.id(),
                created.accessKey(),
                new UpdateRoleCommand(
                        role.name(),
                        "모임 진행과 시간 관리를 맡습니다",
                        minseo.id(),
                        junho.id(),
                        null,
                        null,
                        role.responsibilities(),
                        role.risk()
                )
        );
        DecisionResult updatedDecision = workspaceUseCase.updateDecision(
                created.teamId(),
                created.seasonId(),
                decision.id(),
                created.accessKey(),
                new UpdateDecisionCommand(
                        "진행 순서를 매주 확인한다",
                        decision.reason(),
                        decision.alternative(),
                        minseo.id(),
                        decision.roleIds()
                )
        );

        assertThat(updatedRole.currentMemberId()).isEqualTo(minseo.id());
        assertThat(updatedDecision.authorMemberId()).isEqualTo(minseo.id());
        assertThatThrownBy(() -> workspaceUseCase.createRole(
                created.teamId(),
                created.seasonId(),
                contentIdempotencyKey("inactive-member-new-role"),
                created.accessKey(),
                new CreateRoleCommand(
                        "새 담당 역할",
                        "비활성 구성원을 새로 지정할 수 없습니다",
                        minseo.id(),
                        null,
                        null,
                        null,
                        List.of(),
                        null
                )
        )).isInstanceOfSatisfying(
                DomainValidationException.class,
                exception -> assertThat(exception.getMessage())
                        .isEqualTo("비활성 구성원은 새 담당자나 결정 작성자로 지정할 수 없습니다")
        );
        assertThatThrownBy(() -> workspaceUseCase.updateRole(
                created.teamId(),
                created.seasonId(),
                role.id(),
                created.accessKey(),
                new UpdateRoleCommand(
                        role.name(),
                        role.purpose(),
                        minseo.id(),
                        minseo.id(),
                        null,
                        null,
                        role.responsibilities(),
                        role.risk()
                )
        )).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> workspaceUseCase.createDecision(
                created.teamId(),
                created.seasonId(),
                contentIdempotencyKey("inactive-member-new-decision"),
                created.accessKey(),
                new CreateDecisionCommand(
                        "비활성 작성자 결정",
                        "비활성 구성원은 새 작성자가 될 수 없습니다",
                        "",
                        minseo.id(),
                        List.of(role.id())
                )
        )).isInstanceOf(DomainValidationException.class);

        MemberResult reactivated = workspaceUseCase.updateMemberDeactivation(
                created.teamId(),
                created.seasonId(),
                minseo.id(),
                created.accessKey(),
                false
        );
        RoleResult roleAfterReactivation = workspaceUseCase.createRole(
                created.teamId(),
                created.seasonId(),
                contentIdempotencyKey("reactivated-member-role"),
                created.accessKey(),
                new CreateRoleCommand(
                        "복귀 담당 역할",
                        "복귀한 구성원을 다시 지정합니다",
                        minseo.id(),
                        null,
                        null,
                        null,
                        List.of(),
                        null
                )
        );

        assertThat(reactivated.deactivatedAt()).isNull();
        assertThat(roleAfterReactivation.currentMemberId()).isEqualTo(minseo.id());
    }

    @DisplayName("구성원 비활성화가 먼저 저장되면 새 역할 배정은 공유 잠금 뒤 최신 비활성 상태를 확인한다")
    @Test
    void serializesMemberDeactivationBeforeNewRoleAssignment() throws Exception {
        CreatedWorkspaceResult created = workspaceUseCase.createWorkspace(
                "workspace-member-deactivation-lock-001",
                CREATION_KEY,
                new CreateWorkspaceCommand(
                        "구성원 비활성 잠금 스터디",
                        "파일럿 시즌",
                        LocalDate.of(2026, 7, 21),
                        LocalDate.of(2026, 8, 31),
                        List.of("박민서")
                )
        );
        MemberResult member = workspaceUseCase.getWorkspace(
                created.teamId(),
                created.seasonId(),
                created.accessKey()
        ).members().getFirst();
        CountDownLatch memberUpdateFlushed = new CountDownLatch(1);
        CountDownLatch assignmentLockRequested = new CountDownLatch(1);
        CountDownLatch allowDeactivationCommit = new CountDownLatch(1);
        WorkspaceRepository coordinatedRepository = mock(
                WorkspaceRepository.class,
                delegatesTo(workspaceRepository)
        );
        doAnswer(invocation -> {
            Member saved = workspaceRepository.saveMember(invocation.getArgument(0));
            memberUpdateFlushed.countDown();
            if (!allowDeactivationCommit.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("구성원 비활성화 transaction 해제 대기 시간이 초과됐습니다");
            }
            return saved;
        }).when(coordinatedRepository).saveMember(any(Member.class));
        doAnswer(invocation -> {
            assignmentLockRequested.countDown();
            return workspaceRepository.findMembersByTeamIdAndIdsWithSharedLock(
                    invocation.getArgument(0),
                    invocation.getArgument(1)
            );
        }).when(coordinatedRepository).findMembersByTeamIdAndIdsWithSharedLock(
                any(UUID.class),
                anyList()
        );
        WorkspaceService coordinatedService = new WorkspaceService(
                coordinatedRepository,
                Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC),
                CREATION_KEY,
                RECOVERY_KEY
        );
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<MemberResult> deactivation = executor.submit(() ->
                    transactionTemplate.execute(status ->
                            coordinatedService.updateMemberDeactivation(
                                    created.teamId(),
                                    created.seasonId(),
                                    member.id(),
                                    created.accessKey(),
                                    true
                            ))
            );
            assertThat(memberUpdateFlushed.await(10, TimeUnit.SECONDS)).isTrue();

            Future<Object> assignment = executor.submit(() -> {
                try {
                    return transactionTemplate.execute(status -> coordinatedService.createRole(
                            created.teamId(),
                            created.seasonId(),
                            contentIdempotencyKey("member-deactivation-lock-role"),
                            created.accessKey(),
                            new CreateRoleCommand(
                                    "진행자",
                                    "모임을 진행합니다",
                                    member.id(),
                                    null,
                                    null,
                                    null,
                                    List.of(),
                                    null
                            )
                    ));
                } catch (DomainValidationException exception) {
                    return exception;
                }
            });
            assertThat(assignmentLockRequested.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> assignment.get(300, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            allowDeactivationCommit.countDown();

            assertThat(deactivation.get(10, TimeUnit.SECONDS).deactivatedAt())
                    .isEqualTo(FIXED_INSTANT);
            assertThat(assignment.get(10, TimeUnit.SECONDS))
                    .isInstanceOfSatisfying(
                            DomainValidationException.class,
                            exception -> assertThat(exception.getMessage())
                                    .isEqualTo(
                                            "비활성 구성원은 새 담당자나 결정 작성자로 지정할 수 없습니다"
                                    )
                    );
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM roles WHERE team_id = UUID_TO_BIN(?)",
                    Integer.class,
                    created.teamId().toString()
            )).isZero();
        } finally {
            allowDeactivationCommit.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @DisplayName("같은 버전의 구성원을 읽은 두 저장은 이름과 활동 상태 변경을 모두 커밋할 수 없다")
    @Test
    void rejectsStaleMemberUpdate() {
        CreatedWorkspaceResult created = workspaceUseCase.createWorkspace(
                "workspace-member-optimistic-lock-001",
                CREATION_KEY,
                new CreateWorkspaceCommand(
                        "구성원 충돌 스터디",
                        "파일럿 시즌",
                        LocalDate.of(2026, 7, 21),
                        LocalDate.of(2026, 8, 31),
                        List.of("박민서")
                )
        );
        UUID memberId = workspaceUseCase.getWorkspace(
                created.teamId(),
                created.seasonId(),
                created.accessKey()
        ).members().getFirst().id();
        EntityManager firstEntityManager = entityManagerFactory.createEntityManager();
        EntityManager secondEntityManager = entityManagerFactory.createEntityManager();

        try {
            Member first = firstEntityManager.find(Member.class, memberId);
            Member stale = secondEntityManager.find(Member.class, memberId);
            firstEntityManager.detach(first);
            secondEntityManager.detach(stale);

            first.rename("박민서(진행)");
            stale.updateDeactivation(true, FIXED_INSTANT);
            workspaceRepository.saveMember(first);

            assertThatThrownBy(() -> workspaceRepository.saveMember(stale))
                    .isInstanceOf(WorkspaceContentConflictException.class);
            assertThat(workspaceUseCase.getWorkspace(
                    created.teamId(),
                    created.seasonId(),
                    created.accessKey()
            ).members()).singleElement()
                    .satisfies(member -> {
                        assertThat(member.name()).isEqualTo("박민서(진행)");
                        assertThat(member.deactivatedAt()).isNull();
                    });
        } finally {
            firstEntityManager.close();
            secondEntityManager.close();
        }
    }

    @DisplayName("회차는 생성 시점의 루틴을 스냅샷하고 이후 회차와 완료 상태를 독립적으로 보존한다")
    @Test
    void snapshotsRoutineDefinitionsPerRoundAndKeepsCompletionIndependent() {
        CreatedWorkspaceResult created = workspaceUseCase.createWorkspace(
                "workspace-role-routine-update-success-01",
                CREATION_KEY,
                new CreateWorkspaceCommand(
                        "수정 성공 스터디",
                        "파일럿 시즌",
                        LocalDate.of(2026, 7, 21),
                        LocalDate.of(2026, 9, 30),
                        List.of("박민서", "김준호")
                )
        );
        WorkspaceResult initial = workspaceUseCase.getWorkspace(
                created.teamId(), created.seasonId(), created.accessKey());
        MemberResult minseo = memberNamed(initial, "박민서");
        MemberResult junho = memberNamed(initial, "김준호");
        RoleResult facilitator = workspaceUseCase.createRole(
                created.teamId(),
                created.seasonId(),
                contentIdempotencyKey("update-success-facilitator"),
                created.accessKey(),
                new CreateRoleCommand(
                        "진행자",
                        "모임을 진행합니다",
                        minseo.id(),
                        null,
                        LocalDate.of(2026, 7, 21),
                        LocalDate.of(2026, 8, 31),
                        List.of("시간 확인"),
                        null
                )
        );
        RoleResult recorder = workspaceUseCase.createRole(
                created.teamId(),
                created.seasonId(),
                contentIdempotencyKey("update-success-recorder"),
                created.accessKey(),
                new CreateRoleCommand(
                        "기록자",
                        "결정과 맥락을 기록합니다",
                        junho.id(),
                        null,
                        null,
                        null,
                        List.of("결정 기록"),
                        null
                )
        );
        RoutineResult routine = workspaceUseCase.createRoutine(
                created.teamId(),
                created.seasonId(),
                contentIdempotencyKey("update-success-routine"),
                created.accessKey(),
                new CreateRoutineCommand(
                        "모임 전 질문 모으기",
                        RoutinePhase.BEFORE,
                        "모임 하루 전",
                        facilitator.id(),
                        "질문을 한 문서에 모읍니다"
                )
        );
        CreateSeasonRoundCommand firstRoundCommand = new CreateSeasonRoundCommand(
                "1회차",
                LocalDate.of(2026, 8, 7)
        );
        String firstRoundIdempotencyKey = contentIdempotencyKey("update-success-round-first");
        SeasonRoundResult firstRound = workspaceUseCase.createSeasonRound(
                created.teamId(),
                created.seasonId(),
                firstRoundIdempotencyKey,
                created.accessKey(),
                firstRoundCommand
        );
        RoutineExecutionResult firstExecution = firstRound.routineExecutions().getFirst();
        workspaceUseCase.updateRoutineExecutionCompletion(
                created.teamId(),
                created.seasonId(),
                firstRound.id(),
                firstExecution.id(),
                created.accessKey(),
                true
        );

        RoleResult updatedRole = workspaceUseCase.updateRole(
                created.teamId(),
                created.seasonId(),
                facilitator.id(),
                created.accessKey(),
                new UpdateRoleCommand(
                        "  진행자  ",
                        "  토론 흐름과 시간을 함께 관리합니다  ",
                        junho.id(),
                        minseo.id(),
                        LocalDate.of(2026, 8, 1),
                        LocalDate.of(2026, 9, 30),
                        List.of("  안건 순서 정리  ", "발언 시간 확인"),
                        "  질문이 늦게 모일 수 있습니다  "
                )
        );
        RoutineResult updatedRoutine = workspaceUseCase.updateRoutine(
                created.teamId(),
                created.seasonId(),
                routine.id(),
                created.accessKey(),
                new UpdateRoutineCommand(
                        "  모임 후 결정 정리  ",
                        RoutinePhase.AFTER,
                        "  모임 종료 직후  ",
                        recorder.id(),
                        "  결정과 남은 질문을 문서에 반영합니다  "
                )
        );
        RoutineResult followUpRoutine = workspaceUseCase.createRoutine(
                created.teamId(),
                created.seasonId(),
                contentIdempotencyKey("update-success-routine-follow-up"),
                created.accessKey(),
                new CreateRoutineCommand(
                        "다음 모임 자료 준비",
                        RoutinePhase.BEFORE,
                        "다음 모임 이틀 전",
                        facilitator.id(),
                        "다음 토론 자료와 질문을 미리 공유합니다"
                )
        );

        int roundsBeforeReplay = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM season_rounds WHERE season_id = UUID_TO_BIN(?)",
                Integer.class,
                created.seasonId().toString()
        );
        int executionsBeforeReplay = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM routine_executions WHERE season_round_id = UUID_TO_BIN(?)",
                Integer.class,
                firstRound.id().toString()
        );
        SeasonRoundResult firstRoundReplay = workspaceUseCase.createSeasonRound(
                created.teamId(),
                created.seasonId(),
                firstRoundIdempotencyKey,
                created.accessKey(),
                new CreateSeasonRoundCommand("  1회차  ", firstRoundCommand.meetingDate())
        );
        assertThat(firstRoundReplay.id()).isEqualTo(firstRound.id());
        assertThat(firstRoundReplay.routineExecutions()).singleElement().satisfies(execution -> {
            assertThat(execution.id()).isEqualTo(firstExecution.id());
            assertThat(execution.status()).isEqualTo(RoutineStatus.DONE);
        });
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM season_rounds WHERE season_id = UUID_TO_BIN(?)",
                Integer.class,
                created.seasonId().toString()
        )).isEqualTo(roundsBeforeReplay);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM routine_executions WHERE season_round_id = UUID_TO_BIN(?)",
                Integer.class,
                firstRound.id().toString()
        )).isEqualTo(executionsBeforeReplay);

        SeasonRoundResult secondRound = workspaceUseCase.createSeasonRound(
                created.teamId(),
                created.seasonId(),
                contentIdempotencyKey("update-success-round-second"),
                created.accessKey(),
                new CreateSeasonRoundCommand("2회차", LocalDate.of(2026, 8, 14))
        );
        assertThatThrownBy(() -> workspaceUseCase.updateRoutineExecutionCompletion(
                created.teamId(),
                created.seasonId(),
                secondRound.id(),
                firstExecution.id(),
                created.accessKey(),
                false
        )).isInstanceOfSatisfying(
                WorkspaceNotFoundException.class,
                exception -> assertThat(exception.getCode()).isEqualTo("ROUTINE_EXECUTION_NOT_FOUND")
        );

        assertThat(updatedRole).satisfies(role -> {
            assertThat(role.id()).isEqualTo(facilitator.id());
            assertThat(role.name()).isEqualTo("진행자");
            assertThat(role.purpose()).isEqualTo("토론 흐름과 시간을 함께 관리합니다");
            assertThat(role.currentMemberId()).isEqualTo(junho.id());
            assertThat(role.nextMemberId()).isEqualTo(minseo.id());
            assertThat(role.assignmentStartDate()).isEqualTo(LocalDate.of(2026, 8, 1));
            assertThat(role.assignmentEndDate()).isEqualTo(LocalDate.of(2026, 9, 30));
            assertThat(role.responsibilities()).containsExactly("안건 순서 정리", "발언 시간 확인");
            assertThat(role.risk()).isEqualTo("질문이 늦게 모일 수 있습니다");
        });
        assertThat(updatedRoutine).satisfies(savedRoutine -> {
            assertThat(savedRoutine.id()).isEqualTo(routine.id());
            assertThat(savedRoutine.title()).isEqualTo("모임 후 결정 정리");
            assertThat(savedRoutine.phase()).isEqualTo(RoutinePhase.AFTER);
            assertThat(savedRoutine.dueLabel()).isEqualTo("모임 종료 직후");
            assertThat(savedRoutine.ownerRoleId()).isEqualTo(recorder.id());
            assertThat(savedRoutine.detail()).isEqualTo("결정과 남은 질문을 문서에 반영합니다");
        });

        WorkspaceResult reloaded = workspaceUseCase.getWorkspace(
                created.teamId(), created.seasonId(), created.accessKey());
        assertThat(reloaded.roles()).filteredOn(role -> role.id().equals(facilitator.id()))
                .singleElement().isEqualTo(updatedRole);
        assertThat(reloaded.routines()).filteredOn(savedRoutine -> savedRoutine.id().equals(routine.id()))
                .singleElement().isEqualTo(updatedRoutine);
        assertThat(reloaded.routines()).filteredOn(savedRoutine -> savedRoutine.id().equals(followUpRoutine.id()))
                .singleElement().isEqualTo(followUpRoutine);
        assertThat(reloaded.rounds()).hasSize(2);
        assertThat(reloaded.rounds()).filteredOn(round -> round.id().equals(firstRound.id()))
                .singleElement().satisfies(round ->
                        assertThat(round.routineExecutions()).singleElement().satisfies(execution -> {
                            assertThat(execution.routineId()).isEqualTo(routine.id());
                            assertThat(execution.title()).isEqualTo("모임 전 질문 모으기");
                            assertThat(execution.phase()).isEqualTo(RoutinePhase.BEFORE);
                            assertThat(execution.dueLabel()).isEqualTo("모임 하루 전");
                            assertThat(execution.ownerRoleId()).isEqualTo(facilitator.id());
                            assertThat(execution.status()).isEqualTo(RoutineStatus.DONE);
                            assertThat(execution.detail()).isEqualTo("질문을 한 문서에 모읍니다");
                        }));
        assertThat(reloaded.rounds()).filteredOn(round -> round.id().equals(secondRound.id()))
                .singleElement().satisfies(round -> {
                    assertThat(round.routineExecutions()).hasSize(2);
                    assertThat(round.routineExecutions())
                            .filteredOn(execution -> execution.routineId().equals(routine.id()))
                            .singleElement().satisfies(execution -> {
                                assertThat(execution.title()).isEqualTo("모임 후 결정 정리");
                                assertThat(execution.phase()).isEqualTo(RoutinePhase.AFTER);
                                assertThat(execution.dueLabel()).isEqualTo("모임 종료 직후");
                                assertThat(execution.ownerRoleId()).isEqualTo(recorder.id());
                                assertThat(execution.status()).isEqualTo(RoutineStatus.WAITING);
                                assertThat(execution.detail())
                                        .isEqualTo("결정과 남은 질문을 문서에 반영합니다");
                            });
                    assertThat(round.routineExecutions())
                            .filteredOn(execution -> execution.routineId().equals(followUpRoutine.id()))
                            .singleElement().satisfies(execution -> {
                                assertThat(execution.title()).isEqualTo("다음 모임 자료 준비");
                                assertThat(execution.status()).isEqualTo(RoutineStatus.WAITING);
                            });
                });
    }

    @DisplayName("역할 수정은 대상과 구성원 소속 및 기간과 다른 역할의 중복 이름을 검증한다")
    @Test
    void validatesRoleUpdateOwnershipPeriodAndDuplicateName() {
        CreatedWorkspaceResult created = workspaceUseCase.createWorkspace(
                "workspace-role-update-validation-0001",
                CREATION_KEY,
                new CreateWorkspaceCommand(
                        "역할 수정 검증 스터디",
                        "파일럿 시즌",
                        LocalDate.of(2026, 7, 21),
                        LocalDate.of(2026, 9, 30),
                        List.of("박민서")
                )
        );
        MemberResult member = workspaceUseCase.getWorkspace(
                created.teamId(), created.seasonId(), created.accessKey()).members().getFirst();
        RoleResult facilitator = workspaceUseCase.createRole(
                created.teamId(),
                created.seasonId(),
                contentIdempotencyKey("update-validation-facilitator"),
                created.accessKey(),
                new CreateRoleCommand(
                        "진행자", "모임을 진행합니다", member.id(), null, null, null, List.of(), null)
        );
        workspaceUseCase.createRole(
                created.teamId(),
                created.seasonId(),
                contentIdempotencyKey("update-validation-recorder"),
                created.accessKey(),
                new CreateRoleCommand(
                        "기록자", "결정을 기록합니다", member.id(), null, null, null, List.of(), null)
        );
        CreatedWorkspaceResult other = workspaceUseCase.createWorkspace(
                "workspace-role-update-other-team-0001",
                CREATION_KEY,
                new CreateWorkspaceCommand(
                        "다른 역할 수정 스터디",
                        "다른 시즌",
                        LocalDate.of(2026, 7, 21),
                        LocalDate.of(2026, 9, 30),
                        List.of("다른 구성원")
                )
        );
        WorkspaceResult otherWorkspace = workspaceUseCase.getWorkspace(
                other.teamId(), other.seasonId(), other.accessKey());
        MemberResult otherMember = otherWorkspace.members().getFirst();
        RoleResult otherRole = workspaceUseCase.createRole(
                other.teamId(),
                other.seasonId(),
                contentIdempotencyKey("update-validation-other-role"),
                other.accessKey(),
                new CreateRoleCommand(
                        "다른 역할", "다른 팀 역할입니다", otherMember.id(), null, null, null, List.of(), null)
        );

        assertThatThrownBy(() -> workspaceUseCase.updateRole(
                created.teamId(),
                created.seasonId(),
                facilitator.id(),
                created.accessKey(),
                new UpdateRoleCommand(
                        "  기록자  ", "중복 이름입니다", member.id(), null, null, null, List.of(), null)
        )).isInstanceOf(RoleNameConflictException.class);
        assertThatThrownBy(() -> workspaceUseCase.updateRole(
                created.teamId(),
                created.seasonId(),
                facilitator.id(),
                created.accessKey(),
                new UpdateRoleCommand(
                        "진행자", "다른 팀 구성원입니다", member.id(), otherMember.id(),
                        null, null, List.of(), null)
        )).isInstanceOfSatisfying(
                WorkspaceNotFoundException.class,
                exception -> assertThat(exception.getCode()).isEqualTo("MEMBER_NOT_FOUND")
        );
        assertThatThrownBy(() -> workspaceUseCase.updateRole(
                created.teamId(),
                created.seasonId(),
                facilitator.id(),
                created.accessKey(),
                new UpdateRoleCommand(
                        "진행자", "기간이 잘못됐습니다", member.id(), null,
                        LocalDate.of(2026, 9, 1), LocalDate.of(2026, 8, 31), List.of(), null)
        )).isInstanceOfSatisfying(
                DomainValidationException.class,
                exception -> assertThat(exception.getMessage())
                        .isEqualTo("역할 배정 시작일은 종료일보다 늦을 수 없습니다")
        );
        assertThatThrownBy(() -> workspaceUseCase.updateRole(
                created.teamId(),
                created.seasonId(),
                otherRole.id(),
                created.accessKey(),
                new UpdateRoleCommand(
                        "다른 역할", "다른 팀 역할입니다", null, null, null, null, List.of(), null)
        )).isInstanceOfSatisfying(
                WorkspaceNotFoundException.class,
                exception -> assertThat(exception.getCode()).isEqualTo("ROLE_NOT_FOUND")
        );
        assertThatThrownBy(() -> workspaceUseCase.updateRole(
                created.teamId(),
                created.seasonId(),
                facilitator.id(),
                null,
                new UpdateRoleCommand(
                        "진행자", "접근 키가 없습니다", member.id(), null, null, null, List.of(), null)
        )).isInstanceOf(WorkspaceAccessDeniedException.class);

        assertThat(workspaceUseCase.getWorkspace(
                created.teamId(), created.seasonId(), created.accessKey()).roles())
                .filteredOn(role -> role.id().equals(facilitator.id()))
                .singleElement()
                .satisfies(role -> {
                    assertThat(role.name()).isEqualTo("진행자");
                    assertThat(role.purpose()).isEqualTo("모임을 진행합니다");
                    assertThat(role.nextMemberId()).isNull();
                    assertThat(role.assignmentStartDate()).isNull();
                    assertThat(role.assignmentEndDate()).isNull();
                });
    }

    @DisplayName("루틴 수정은 대상 시즌과 담당 역할의 팀 소속을 검증한다")
    @Test
    void validatesRoutineUpdateSeasonAndOwnerRoleOwnership() {
        CreatedWorkspaceResult created = workspaceUseCase.createWorkspace(
                "workspace-routine-update-validation-01",
                CREATION_KEY,
                new CreateWorkspaceCommand(
                        "루틴 수정 검증 스터디",
                        "파일럿 시즌",
                        LocalDate.of(2026, 7, 21),
                        LocalDate.of(2026, 9, 30),
                        List.of("박민서")
                )
        );
        RoleResult role = workspaceUseCase.createRole(
                created.teamId(),
                created.seasonId(),
                contentIdempotencyKey("routine-update-validation-role"),
                created.accessKey(),
                new CreateRoleCommand(
                        "진행자", "모임을 진행합니다", null, null, null, null, List.of(), null)
        );
        RoutineResult routine = workspaceUseCase.createRoutine(
                created.teamId(),
                created.seasonId(),
                contentIdempotencyKey("routine-update-validation-routine"),
                created.accessKey(),
                new CreateRoutineCommand(
                        "질문 모으기", RoutinePhase.BEFORE, "모임 전", role.id(), "질문을 모읍니다")
        );
        SeasonRoundResult validationRound = workspaceUseCase.createSeasonRound(
                created.teamId(),
                created.seasonId(),
                contentIdempotencyKey("routine-update-validation-round"),
                created.accessKey(),
                new CreateSeasonRoundCommand("검증 회차", LocalDate.of(2026, 8, 1))
        );
        RoutineExecutionResult validationExecution = validationRound.routineExecutions().getFirst();
        workspaceUseCase.updateRoutineExecutionCompletion(
                created.teamId(),
                created.seasonId(),
                validationRound.id(),
                validationExecution.id(),
                created.accessKey(),
                true
        );

        CreatedWorkspaceResult other = workspaceUseCase.createWorkspace(
                "workspace-routine-update-other-team-01",
                CREATION_KEY,
                new CreateWorkspaceCommand(
                        "다른 루틴 수정 스터디",
                        "다른 시즌",
                        LocalDate.of(2026, 7, 21),
                        LocalDate.of(2026, 9, 30),
                        List.of("다른 구성원")
                )
        );
        RoleResult otherRole = workspaceUseCase.createRole(
                other.teamId(),
                other.seasonId(),
                contentIdempotencyKey("routine-update-other-role"),
                other.accessKey(),
                new CreateRoleCommand(
                        "다른 진행자", "다른 팀 역할입니다", null, null, null, null, List.of(), null)
        );
        RoutineResult otherRoutine = workspaceUseCase.createRoutine(
                other.teamId(),
                other.seasonId(),
                contentIdempotencyKey("routine-update-other-routine"),
                other.accessKey(),
                new CreateRoutineCommand(
                        "다른 루틴", RoutinePhase.DURING, "모임 중", otherRole.id(), "다른 팀 루틴입니다")
        );

        assertThatThrownBy(() -> workspaceUseCase.updateRoutine(
                created.teamId(),
                created.seasonId(),
                routine.id(),
                created.accessKey(),
                new UpdateRoutineCommand(
                        "잘못된 담당 역할", RoutinePhase.DURING, "모임 중", otherRole.id(), "수정할 수 없습니다")
        )).isInstanceOfSatisfying(
                WorkspaceNotFoundException.class,
                exception -> assertThat(exception.getCode()).isEqualTo("ROLE_NOT_FOUND")
        );
        assertThatThrownBy(() -> workspaceUseCase.updateRoutine(
                created.teamId(),
                created.seasonId(),
                otherRoutine.id(),
                created.accessKey(),
                new UpdateRoutineCommand(
                        "다른 시즌 루틴", RoutinePhase.DURING, "모임 중", role.id(), "수정할 수 없습니다")
        )).isInstanceOfSatisfying(
                WorkspaceNotFoundException.class,
                exception -> assertThat(exception.getCode()).isEqualTo("ROUTINE_NOT_FOUND")
        );
        assertThatThrownBy(() -> workspaceUseCase.updateRoutine(
                created.teamId(),
                created.seasonId(),
                routine.id(),
                null,
                new UpdateRoutineCommand(
                        "접근 키 없는 수정", RoutinePhase.AFTER, "모임 후", role.id(), "수정할 수 없습니다")
        )).isInstanceOf(WorkspaceAccessDeniedException.class);

        assertThat(workspaceUseCase.getWorkspace(
                created.teamId(), created.seasonId(), created.accessKey()).routines())
                .filteredOn(savedRoutine -> savedRoutine.id().equals(routine.id()))
                .singleElement()
                .satisfies(savedRoutine -> {
                    assertThat(savedRoutine.title()).isEqualTo("질문 모으기");
                    assertThat(savedRoutine.ownerRoleId()).isEqualTo(role.id());
                });
        assertThat(workspaceUseCase.getWorkspace(
                created.teamId(), created.seasonId(), created.accessKey()).rounds())
                .filteredOn(round -> round.id().equals(validationRound.id()))
                .singleElement()
                .satisfies(round -> assertThat(round.routineExecutions())
                        .singleElement()
                        .satisfies(execution -> assertThat(execution.status()).isEqualTo(RoutineStatus.DONE)));
    }

    @DisplayName("회차 생성은 시즌 기간과 시즌 안의 이름 유일성을 검증한다")
    @Test
    void validatesSeasonRoundDateAndUniqueName() {
        CreatedWorkspaceResult created = workspaceUseCase.createWorkspace(
                "workspace-round-validation-000001",
                CREATION_KEY,
                new CreateWorkspaceCommand(
                        "회차 검증 스터디",
                        "파일럿 시즌",
                        LocalDate.of(2026, 7, 21),
                        LocalDate.of(2026, 8, 31),
                        List.of("박민서")
                )
        );
        workspaceUseCase.createSeasonRound(
                created.teamId(),
                created.seasonId(),
                contentIdempotencyKey("round-validation-first"),
                created.accessKey(),
                new CreateSeasonRoundCommand("1회차", LocalDate.of(2026, 7, 21))
        );

        assertThatThrownBy(() -> workspaceUseCase.createSeasonRound(
                created.teamId(),
                created.seasonId(),
                contentIdempotencyKey("round-validation-outside-season"),
                created.accessKey(),
                new CreateSeasonRoundCommand("시즌 밖 회차", LocalDate.of(2026, 9, 1))
        )).isInstanceOfSatisfying(
                DomainValidationException.class,
                exception -> assertThat(exception.getMessage())
                        .isEqualTo("모임 날짜는 시즌 기간 안에 있어야 합니다")
        );
        assertThatThrownBy(() -> workspaceUseCase.createSeasonRound(
                created.teamId(),
                created.seasonId(),
                contentIdempotencyKey("round-validation-duplicate-name"),
                created.accessKey(),
                new CreateSeasonRoundCommand("  1회차  ", LocalDate.of(2026, 7, 28))
        )).isInstanceOf(SeasonRoundNameConflictException.class);
    }

    @DisplayName("회차 수정과 가역 보관은 실행 스냅샷과 완료 상태를 그대로 보존한다")
    @Test
    void revisesAndArchivesSeasonRoundWithoutChangingExecutions() {
        CreatedWorkspaceResult created = workspaceUseCase.createWorkspace(
                "workspace-round-revision-archive-001",
                CREATION_KEY,
                new CreateWorkspaceCommand(
                        "회차 정정 스터디",
                        "파일럿 시즌",
                        LocalDate.of(2026, 7, 21),
                        LocalDate.of(2026, 8, 31),
                        List.of("박민서")
                )
        );
        RoleResult role = workspaceUseCase.createRole(
                created.teamId(),
                created.seasonId(),
                contentIdempotencyKey("round-revision-role"),
                created.accessKey(),
                new CreateRoleCommand(
                        "진행자", "모임을 진행합니다", null, null, null, null, List.of(), null)
        );
        RoutineResult routine = workspaceUseCase.createRoutine(
                created.teamId(),
                created.seasonId(),
                contentIdempotencyKey("round-revision-routine"),
                created.accessKey(),
                new CreateRoutineCommand(
                        "질문 모으기",
                        RoutinePhase.BEFORE,
                        "모임 하루 전",
                        role.id(),
                        "질문을 공통 문서에 모읍니다"
                )
        );
        SeasonRoundResult createdRound = workspaceUseCase.createSeasonRound(
                created.teamId(),
                created.seasonId(),
                contentIdempotencyKey("round-revision-round"),
                created.accessKey(),
                new CreateSeasonRoundCommand("1회차", LocalDate.of(2026, 7, 28))
        );
        RoutineExecutionResult execution = createdRound.routineExecutions().getFirst();
        workspaceUseCase.updateRoutineExecutionCompletion(
                created.teamId(),
                created.seasonId(),
                createdRound.id(),
                execution.id(),
                created.accessKey(),
                true
        );

        SeasonRoundResult updated = workspaceUseCase.updateSeasonRound(
                created.teamId(),
                created.seasonId(),
                createdRound.id(),
                created.accessKey(),
                new UpdateSeasonRoundCommand("  첫 모임  ", LocalDate.of(2026, 7, 29))
        );

        assertThat(updated).satisfies(round -> {
            assertThat(round.id()).isEqualTo(createdRound.id());
            assertThat(round.name()).isEqualTo("첫 모임");
            assertThat(round.meetingDate()).isEqualTo(LocalDate.of(2026, 7, 29));
            assertThat(round.archivedAt()).isNull();
            assertThat(round.routineExecutions()).singleElement().satisfies(savedExecution -> {
                assertThat(savedExecution.id()).isEqualTo(execution.id());
                assertThat(savedExecution.routineId()).isEqualTo(routine.id());
                assertThat(savedExecution.title()).isEqualTo("질문 모으기");
                assertThat(savedExecution.status()).isEqualTo(RoutineStatus.DONE);
            });
        });

        SeasonRoundResult archived = workspaceUseCase.updateSeasonRoundArchive(
                created.teamId(),
                created.seasonId(),
                createdRound.id(),
                created.accessKey(),
                true
        );
        SeasonRoundResult repeatedlyArchived = workspaceUseCase.updateSeasonRoundArchive(
                created.teamId(),
                created.seasonId(),
                createdRound.id(),
                created.accessKey(),
                true
        );

        assertThat(archived.archivedAt()).isEqualTo(FIXED_INSTANT);
        assertThat(repeatedlyArchived.archivedAt()).isEqualTo(FIXED_INSTANT);
        assertThat(repeatedlyArchived.routineExecutions()).singleElement()
                .satisfies(savedExecution -> {
                    assertThat(savedExecution.id()).isEqualTo(execution.id());
                    assertThat(savedExecution.status()).isEqualTo(RoutineStatus.DONE);
                });
        assertThat(workspaceUseCase.getWorkspace(
                created.teamId(),
                created.seasonId(),
                created.accessKey()
        ).rounds()).filteredOn(round -> round.id().equals(createdRound.id()))
                .singleElement()
                .satisfies(round -> assertThat(round.archivedAt()).isEqualTo(FIXED_INSTANT));
        SeasonRoundResult replayedAfterRevisionAndArchive = workspaceUseCase.createSeasonRound(
                created.teamId(),
                created.seasonId(),
                contentIdempotencyKey("round-revision-round"),
                created.accessKey(),
                new CreateSeasonRoundCommand("  1회차  ", LocalDate.of(2026, 7, 28))
        );
        assertThat(replayedAfterRevisionAndArchive).satisfies(round -> {
            assertThat(round.id()).isEqualTo(createdRound.id());
            assertThat(round.name()).isEqualTo("첫 모임");
            assertThat(round.meetingDate()).isEqualTo(LocalDate.of(2026, 7, 29));
            assertThat(round.archivedAt()).isEqualTo(FIXED_INSTANT);
            assertThat(round.routineExecutions()).singleElement()
                    .satisfies(savedExecution -> assertThat(savedExecution.status()).isEqualTo(RoutineStatus.DONE));
        });
        assertThatThrownBy(() -> workspaceUseCase.createSeasonRound(
                created.teamId(),
                created.seasonId(),
                contentIdempotencyKey("round-revision-round"),
                created.accessKey(),
                new CreateSeasonRoundCommand("첫 모임", LocalDate.of(2026, 7, 29))
        )).isInstanceOf(IdempotencyKeyReusedException.class);

        assertThatThrownBy(() -> workspaceUseCase.updateSeasonRound(
                created.teamId(),
                created.seasonId(),
                createdRound.id(),
                created.accessKey(),
                new UpdateSeasonRoundCommand("보관 중 수정", LocalDate.of(2026, 7, 30))
        )).isInstanceOfSatisfying(
                WorkspaceNotFoundException.class,
                exception -> assertThat(exception.getCode()).isEqualTo("SEASON_ROUND_NOT_FOUND")
        );
        assertThatThrownBy(() -> workspaceUseCase.updateRoutineExecutionCompletion(
                created.teamId(),
                created.seasonId(),
                createdRound.id(),
                execution.id(),
                created.accessKey(),
                false
        )).isInstanceOfSatisfying(
                WorkspaceNotFoundException.class,
                exception -> assertThat(exception.getCode()).isEqualTo("SEASON_ROUND_NOT_FOUND")
        );

        SeasonRoundResult restored = workspaceUseCase.updateSeasonRoundArchive(
                created.teamId(),
                created.seasonId(),
                createdRound.id(),
                created.accessKey(),
                false
        );
        assertThat(restored.archivedAt()).isNull();
        assertThat(restored.routineExecutions()).singleElement()
                .satisfies(savedExecution -> assertThat(savedExecution.status()).isEqualTo(RoutineStatus.DONE));
    }

    @DisplayName("회차 수정은 시즌 기간과 다른 회차의 중복 이름을 검증한다")
    @Test
    void validatesSeasonRoundRevisionDateAndUniqueName() {
        CreatedWorkspaceResult created = workspaceUseCase.createWorkspace(
                "workspace-round-revision-validation-1",
                CREATION_KEY,
                new CreateWorkspaceCommand(
                        "회차 수정 검증 스터디",
                        "파일럿 시즌",
                        LocalDate.of(2026, 7, 21),
                        LocalDate.of(2026, 8, 31),
                        List.of("박민서")
                )
        );
        SeasonRoundResult firstRound = workspaceUseCase.createSeasonRound(
                created.teamId(),
                created.seasonId(),
                contentIdempotencyKey("round-revision-validation-first"),
                created.accessKey(),
                new CreateSeasonRoundCommand("1회차", LocalDate.of(2026, 7, 28))
        );
        SeasonRoundResult secondRound = workspaceUseCase.createSeasonRound(
                created.teamId(),
                created.seasonId(),
                contentIdempotencyKey("round-revision-validation-second"),
                created.accessKey(),
                new CreateSeasonRoundCommand("2회차", LocalDate.of(2026, 8, 4))
        );

        assertThat(workspaceUseCase.updateSeasonRound(
                created.teamId(),
                created.seasonId(),
                firstRound.id(),
                created.accessKey(),
                new UpdateSeasonRoundCommand("  1회차  ", LocalDate.of(2026, 7, 29))
        ).meetingDate()).isEqualTo(LocalDate.of(2026, 7, 29));
        workspaceUseCase.updateSeasonRoundArchive(
                created.teamId(),
                created.seasonId(),
                firstRound.id(),
                created.accessKey(),
                true
        );
        assertThatThrownBy(() -> workspaceUseCase.updateSeasonRound(
                created.teamId(),
                created.seasonId(),
                secondRound.id(),
                created.accessKey(),
                new UpdateSeasonRoundCommand("1회차", LocalDate.of(2026, 8, 5))
        )).isInstanceOf(SeasonRoundNameConflictException.class);
        assertThatThrownBy(() -> workspaceUseCase.updateSeasonRound(
                created.teamId(),
                created.seasonId(),
                secondRound.id(),
                created.accessKey(),
                new UpdateSeasonRoundCommand("시즌 밖 회차", LocalDate.of(2026, 9, 1))
        )).isInstanceOfSatisfying(
                DomainValidationException.class,
                exception -> assertThat(exception.getMessage())
                        .isEqualTo("모임 날짜는 시즌 기간 안에 있어야 합니다")
        );
    }

    @DisplayName("역할 자료는 사용자 정보가 없는 http 또는 https 주소만 허용한다")
    @Test
    void validatesRoleResourceUrlBoundary() {
        CreatedWorkspaceResult created = workspaceUseCase.createWorkspace(
                "workspace-resource-url-validation-001",
                CREATION_KEY,
                new CreateWorkspaceCommand(
                        "자료 URL 검증 스터디",
                        "파일럿 시즌",
                        LocalDate.of(2026, 7, 21),
                        LocalDate.of(2026, 8, 31),
                        List.of("박민서")
                )
        );
        RoleResult role = workspaceUseCase.createRole(
                created.teamId(),
                created.seasonId(),
                contentIdempotencyKey("resource-url-role"),
                created.accessKey(),
                new CreateRoleCommand("진행자", "모임을 진행합니다", null, null, null, null, List.of(), null)
        );
        int reservationsBefore = contentReservationCount(created.teamId());

        List<String> invalidUrls = List.of(
                "file:///etc/passwd",
                "javascript:alert(1)",
                "https://user:secret@example.com/private",
                "https:///missing-host",
                "not-a-url"
        );
        for (int index = 0; index < invalidUrls.size(); index++) {
            String invalidUrl = invalidUrls.get(index);
            int requestIndex = index;
            assertThatThrownBy(() -> workspaceUseCase.createRoleResource(
                    created.teamId(),
                    created.seasonId(),
                    contentIdempotencyKey("resource-url-invalid-" + requestIndex),
                    created.accessKey(),
                    new CreateRoleResourceCommand(role.id(), "검증 자료", invalidUrl, null)
            )).isInstanceOf(DomainValidationException.class);
        }

        assertThat(contentReservationCount(created.teamId())).isEqualTo(reservationsBefore);
    }

    @DisplayName("같은 콘텐츠 멱등 키는 여덟 작업에서 독립적으로 재생되고 다른 요청 재사용은 거절된다")
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

        MemberResult addedMember = workspaceUseCase.createMember(
                created.teamId(),
                created.seasonId(),
                sharedRawKey,
                created.accessKey(),
                new CreateMemberCommand("  김준호  ")
        );
        MemberResult addedMemberReplay = workspaceUseCase.createMember(
                created.teamId(),
                created.seasonId(),
                sharedRawKey,
                created.accessKey(),
                new CreateMemberCommand("김준호")
        );

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

        SeasonRoundResult round = workspaceUseCase.createSeasonRound(
                created.teamId(),
                created.seasonId(),
                sharedRawKey,
                created.accessKey(),
                new CreateSeasonRoundCommand("  1회차  ", LocalDate.of(2026, 7, 28))
        );
        RoutineExecutionResult roundExecution = round.routineExecutions().getFirst();
        workspaceUseCase.updateRoutineExecutionCompletion(
                created.teamId(),
                created.seasonId(),
                round.id(),
                roundExecution.id(),
                created.accessKey(),
                true
        );
        SeasonRoundResult roundReplay = workspaceUseCase.createSeasonRound(
                created.teamId(),
                created.seasonId(),
                sharedRawKey,
                created.accessKey(),
                new CreateSeasonRoundCommand("1회차", LocalDate.of(2026, 7, 28))
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

        RoleResourceResult resource = workspaceUseCase.createRoleResource(
                created.teamId(),
                created.seasonId(),
                sharedRawKey,
                created.accessKey(),
                new CreateRoleResourceCommand(
                        role.id(),
                        "  질문 정리 가이드  ",
                        "  https://docs.example.com/question-guide  ",
                        "   "
                )
        );
        RoleResourceResult resourceReplay = workspaceUseCase.createRoleResource(
                created.teamId(),
                created.seasonId(),
                sharedRawKey,
                created.accessKey(),
                new CreateRoleResourceCommand(
                        role.id(),
                        "질문 정리 가이드",
                        "https://docs.example.com/question-guide",
                        null
                )
        );

        RoleHandoffTransitionResult roleHandoff = workspaceUseCase.prepareRoleHandoff(
                created.teamId(),
                created.seasonId(),
                role.id(),
                sharedRawKey,
                created.accessKey(),
                new WorkspaceUseCase.PrepareRoleHandoffCommand(
                        addedMember.id(),
                        LocalDate.of(2026, 8, 1),
                        LocalDate.of(2026, 8, 31)
                )
        );
        RoleHandoffTransitionResult roleHandoffReplay = workspaceUseCase.prepareRoleHandoff(
                created.teamId(),
                created.seasonId(),
                role.id(),
                sharedRawKey,
                created.accessKey(),
                new WorkspaceUseCase.PrepareRoleHandoffCommand(
                        addedMember.id(),
                        LocalDate.of(2026, 8, 1),
                        LocalDate.of(2026, 8, 31)
                )
        );

        assertThat(addedMemberReplay).isEqualTo(addedMember);
        assertThat(roleReplay).isEqualTo(role);
        assertThat(routineReplay).isEqualTo(routine);
        assertThat(roundReplay.id()).isEqualTo(round.id());
        assertThat(roundReplay.routineExecutions()).singleElement().satisfies(execution -> {
            assertThat(execution.id()).isEqualTo(roundExecution.id());
            assertThat(execution.status()).isEqualTo(RoutineStatus.DONE);
        });
        assertThat(decisionReplay).isEqualTo(decision);
        assertThat(handoffReplay.id()).isEqualTo(handoff.id());
        assertThat(handoffReplay.completed()).isTrue();
        assertThat(resourceReplay).isEqualTo(resource);
        assertThat(roleHandoffReplay).isEqualTo(roleHandoff);
        assertThatThrownBy(() -> workspaceUseCase.createMember(
                created.teamId(),
                created.seasonId(),
                sharedRawKey,
                created.accessKey(),
                new CreateMemberCommand("다른 구성원")
        )).isInstanceOf(IdempotencyKeyReusedException.class);
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
        assertThatThrownBy(() -> workspaceUseCase.createSeasonRound(
                created.teamId(),
                created.seasonId(),
                sharedRawKey,
                created.accessKey(),
                new CreateSeasonRoundCommand("다른 회차", LocalDate.of(2026, 7, 29))
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
        assertThatThrownBy(() -> workspaceUseCase.createRoleResource(
                created.teamId(),
                created.seasonId(),
                sharedRawKey,
                created.accessKey(),
                new CreateRoleResourceCommand(
                        role.id(),
                        "질문 정리 가이드",
                        "https://docs.example.com/other-guide",
                        null
                )
        )).isInstanceOf(IdempotencyKeyReusedException.class);
        assertThatThrownBy(() -> workspaceUseCase.prepareRoleHandoff(
                created.teamId(),
                created.seasonId(),
                role.id(),
                sharedRawKey,
                created.accessKey(),
                new WorkspaceUseCase.PrepareRoleHandoffCommand(
                        addedMember.id(),
                        LocalDate.of(2026, 8, 2),
                        LocalDate.of(2026, 8, 31)
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
                .hasSize(8)
                .doesNotHaveDuplicates()
                .allSatisfy(hash -> assertThat(hash)
                        .matches("[0-9a-f]{64}")
                        .doesNotContain(sharedRawKey));
        assertThat(jdbcTemplate.queryForList(
                "SELECT operation FROM content_creation_idempotency "
                        + "WHERE team_id = UUID_TO_BIN(?) ORDER BY operation",
                String.class,
                created.teamId().toString()
        )).containsExactly(
                "DECISION",
                "HANDOFF_ITEM",
                "MEMBER",
                "ROLE",
                "ROLE_HANDOFF",
                "ROLE_RESOURCE",
                "ROUND",
                "ROUTINE"
        );
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM members WHERE team_id = UUID_TO_BIN(?)",
                Integer.class,
                created.teamId().toString()
        )).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM routines WHERE season_id = UUID_TO_BIN(?)",
                Integer.class,
                created.seasonId().toString()
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM season_rounds WHERE season_id = UUID_TO_BIN(?)",
                Integer.class,
                created.seasonId().toString()
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM routine_executions WHERE season_round_id = UUID_TO_BIN(?)",
                Integer.class,
                round.id().toString()
        )).isEqualTo(1);
    }

    @DisplayName("같은 구성원 멱등 키는 팀과 시즌 경로가 다르면 독립된 생성 요청으로 처리된다")
    @Test
    void scopesMemberIdempotencyToTeamAndSeasonPath() {
        CreatedWorkspaceResult created = workspaceUseCase.createWorkspace(
                "workspace-member-season-scope-000001",
                CREATION_KEY,
                new CreateWorkspaceCommand(
                        "구성원 시즌 범위 스터디",
                        "첫 시즌",
                        LocalDate.of(2026, 7, 21),
                        LocalDate.of(2026, 8, 31),
                        List.of("박민서")
                )
        );
        String sharedIdempotencyKey = contentIdempotencyKey("member-season-scope");

        MemberResult firstSeasonMember = workspaceUseCase.createMember(
                created.teamId(),
                created.seasonId(),
                sharedIdempotencyKey,
                created.accessKey(),
                new CreateMemberCommand("김준호")
        );
        workspaceUseCase.updateSeasonEnding(
                created.teamId(),
                created.seasonId(),
                created.accessKey(),
                true
        );
        UUID nextSeasonId = UUID.randomUUID();
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                workspaceRepository.saveSeason(Season.createSuccessor(
                        nextSeasonId,
                        created.teamId(),
                        created.seasonId(),
                        "다음 시즌",
                        LocalDate.of(2026, 9, 1),
                        LocalDate.of(2026, 10, 31)
                ))
        );
        MemberResult nextSeasonMember = workspaceUseCase.createMember(
                created.teamId(),
                nextSeasonId,
                sharedIdempotencyKey,
                created.accessKey(),
                new CreateMemberCommand("이지연")
        );

        assertThat(firstSeasonMember.id()).isNotEqualTo(nextSeasonMember.id());
        assertThat(jdbcTemplate.queryForList(
                "SELECT idempotency_hash FROM content_creation_idempotency "
                        + "WHERE team_id = UUID_TO_BIN(?) AND operation = 'MEMBER'",
                String.class,
                created.teamId().toString()
        )).hasSize(2).doesNotHaveDuplicates();
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
        assertThatThrownBy(() -> workspaceUseCase.createMember(
                created.teamId(),
                created.seasonId(),
                contentIdempotencyKey("rollback-duplicate-member"),
                created.accessKey(),
                new CreateMemberCommand("  박민서  ")
        )).isInstanceOf(MemberNameConflictException.class);
        assertThatThrownBy(() -> workspaceUseCase.createHandoffItem(
                created.teamId(),
                created.seasonId(),
                contentIdempotencyKey("rollback-wrong-access"),
                "wrong-access-key",
                new CreateHandoffItemCommand(UUID.randomUUID(), "잘못된 요청", HandoffCategory.ADVICE)
        )).isInstanceOf(WorkspaceAccessDeniedException.class);
        assertThatThrownBy(() -> workspaceUseCase.createRoleResource(
                created.teamId(),
                created.seasonId(),
                contentIdempotencyKey("rollback-resource-invalid-role"),
                created.accessKey(),
                new CreateRoleResourceCommand(
                        UUID.randomUUID(),
                        "잘못된 자료",
                        "https://docs.example.com/invalid-role",
                        null
                )
        )).isInstanceOfSatisfying(
                WorkspaceNotFoundException.class,
                exception -> assertThat(exception.getCode()).isEqualTo("ROLE_NOT_FOUND")
        );

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
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @DisplayName("서로 다른 멱등 키로 같은 구성원을 동시에 만들면 한 건만 저장되고 실패한 예약은 롤백된다")
    @Test
    void serializesConcurrentMemberNameWithDatabaseConstraintAndRollsBackReservation() throws Exception {
        CreatedWorkspaceResult created = workspaceUseCase.createWorkspace(
                "workspace-concurrent-member-name-0001",
                CREATION_KEY,
                new CreateWorkspaceCommand(
                        "동시 구성원 스터디",
                        "파일럿 시즌",
                        LocalDate.of(2026, 7, 21),
                        LocalDate.of(2026, 8, 31),
                        List.of("박민서")
                )
        );
        int reservationsBefore = contentReservationCount(created.teamId());
        String firstIdempotencyKey = contentIdempotencyKey("concurrent-member-first");
        String secondIdempotencyKey = contentIdempotencyKey("concurrent-member-second");
        CreateMemberCommand command = new CreateMemberCommand("김준호");
        CyclicBarrier bothRequestsReadNoExistingMember = new CyclicBarrier(2);
        WorkspaceRepository synchronizedRepository = mock(
                WorkspaceRepository.class,
                delegatesTo(workspaceRepository)
        );
        doAnswer(invocation -> {
            boolean exists = workspaceRepository.existsMemberByTeamIdAndName(
                    invocation.getArgument(0),
                    invocation.getArgument(1)
            );
            bothRequestsReadNoExistingMember.await(10, TimeUnit.SECONDS);
            return exists;
        }).when(synchronizedRepository).existsMemberByTeamIdAndName(
                any(UUID.class),
                anyString()
        );
        WorkspaceService synchronizedService = new WorkspaceService(
                synchronizedRepository,
                Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC),
                CREATION_KEY,
                RECOVERY_KEY
        );
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Object> first = executor.submit(() -> runMemberCreationTransaction(
                    transactionTemplate,
                    synchronizedService,
                    created,
                    firstIdempotencyKey,
                    command
            ));
            Future<Object> second = executor.submit(() -> runMemberCreationTransaction(
                    transactionTemplate,
                    synchronizedService,
                    created,
                    secondIdempotencyKey,
                    command
            ));
            Object firstOutcome = first.get(30, TimeUnit.SECONDS);
            Object secondOutcome = second.get(30, TimeUnit.SECONDS);
            List<Object> outcomes = List.of(firstOutcome, secondOutcome);

            assertThat(outcomes).filteredOn(MemberResult.class::isInstance).hasSize(1);
            assertThat(outcomes).filteredOn(MemberNameConflictException.class::isInstance).hasSize(1);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM members WHERE team_id = UUID_TO_BIN(?) AND name = ?",
                    Integer.class,
                    created.teamId().toString(),
                    "김준호"
            )).isEqualTo(1);
            assertThat(contentReservationCount(created.teamId())).isEqualTo(reservationsBefore + 1);

            String rolledBackIdempotencyKey = firstOutcome instanceof MemberNameConflictException
                    ? firstIdempotencyKey
                    : secondIdempotencyKey;
            MemberResult retried = workspaceUseCase.createMember(
                    created.teamId(),
                    created.seasonId(),
                    rolledBackIdempotencyKey,
                    created.accessKey(),
                    new CreateMemberCommand("이지연")
            );
            assertThat(retried.name()).isEqualTo("이지연");
            assertThat(contentReservationCount(created.teamId())).isEqualTo(reservationsBefore + 2);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
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
                "INSERT INTO seasons (id, team_id, name, start_date, end_date, ended_at) "
                        + "VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?, ?, ?)",
                "00000000-0000-0000-0000-000000000001",
                first.teamId().toString(),
                "추가 시즌",
                LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 12, 31),
                FIXED_INSTANT
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

    @DisplayName("로그인 생성은 선택한 초기 구성원을 OWNER로 결속하고 접근 키 없이 재생한다")
    @Test
    void createsOwnedWorkspaceAtomicallyAndReplaysWithoutAccessKey() throws Exception {
        UUID accountId = UUID.fromString("81000000-0000-4000-8000-000000000001");
        jdbcTemplate.update(
                "INSERT INTO user_accounts (id, created_at) VALUES (UUID_TO_BIN(?), ?)",
                accountId.toString(),
                FIXED_INSTANT.minusSeconds(60)
        );
        String idempotencyKey = "workspace-owner-idempotency-retry-0001";
        CreateWorkspaceCommand command = new CreateWorkspaceCommand(
                "OWNER 원자 결속 스터디",
                "파일럿 시즌",
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 10, 31),
                List.of("박민서", "김준호")
        );
        AuthenticatedAccount account = new AuthenticatedAccount(accountId);

        CreatedWorkspaceResult created = workspaceUseCase.createWorkspaceForOwner(
                idempotencyKey,
                account,
                "  박민서  ",
                command
        );
        CreatedWorkspaceResult replayed = workspaceUseCase.createWorkspaceForOwner(
                idempotencyKey,
                account,
                "박민서",
                new CreateWorkspaceCommand(
                        " OWNER 원자 결속 스터디 ",
                        "파일럿 시즌",
                        LocalDate.of(2026, 8, 1),
                        LocalDate.of(2026, 10, 31),
                        List.of("김준호", "박민서")
                )
        );

        assertThat(created.accessKey()).isNull();
        assertThat(replayed).isEqualTo(created);
        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM member_identity_bindings binding
                JOIN members member ON member.id = binding.member_id
                WHERE binding.team_id = UUID_TO_BIN(?)
                  AND binding.user_account_id = UUID_TO_BIN(?)
                  AND binding.role = 'OWNER'
                  AND member.name = ?
                """,
                Integer.class,
                created.teamId().toString(),
                accountId.toString(),
                "박민서"
        )).isEqualTo(1);

        WorkspaceResult workspace = workspaceUseCase.getWorkspaceAuthorized(
                created.teamId(),
                created.seasonId(),
                new SessionAccount(account)
        );
        assertThat(workspace.team().name()).isEqualTo("OWNER 원자 결속 스터디");
        assertThatThrownBy(() -> workspaceUseCase.getWorkspace(
                created.teamId(),
                created.seasonId(),
                legacyInitialAccessKey(idempotencyKey)
        )).isInstanceOf(WorkspaceAccessDeniedException.class);

        workspaceUseCase.recoverAccessKey(
                created.teamId(),
                created.seasonId(),
                "workspace-owner-recovery-idempotency-01",
                RECOVERY_KEY
        );
        assertThat(workspaceUseCase.createWorkspaceForOwner(
                idempotencyKey,
                account,
                "박민서",
                command
        )).isEqualTo(created);
    }

    @DisplayName("로그인 생성 재생은 OWNER의 이름과 활동 상태가 바뀌어도 최초 식별자를 반환한다")
    @Test
    void replaysOwnedWorkspaceAfterOwnerRenameAndDeactivation() {
        UUID accountId = UUID.fromString("81000000-0000-4000-8000-000000000005");
        jdbcTemplate.update(
                "INSERT INTO user_accounts (id, created_at) VALUES (UUID_TO_BIN(?), ?)",
                accountId.toString(),
                FIXED_INSTANT.minusSeconds(60)
        );
        String idempotencyKey = "workspace-owner-idempotency-member-change-01";
        CreateWorkspaceCommand command = new CreateWorkspaceCommand(
                "OWNER 변경 뒤 재생 스터디",
                "파일럿 시즌",
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 10, 31),
                List.of("박민서", "김준호")
        );
        AuthenticatedAccount account = new AuthenticatedAccount(accountId);
        CreatedWorkspaceResult created = workspaceUseCase.createWorkspaceForOwner(
                idempotencyKey,
                account,
                "박민서",
                command
        );
        String ownerMemberId = jdbcTemplate.queryForObject(
                """
                SELECT BIN_TO_UUID(member_id)
                FROM member_identity_bindings
                WHERE team_id = UUID_TO_BIN(?)
                  AND user_account_id = UUID_TO_BIN(?)
                  AND role = 'OWNER'
                """,
                String.class,
                created.teamId().toString(),
                accountId.toString()
        );

        jdbcTemplate.update(
                "UPDATE members SET name = ? WHERE id = UUID_TO_BIN(?)",
                "박민서 변경",
                ownerMemberId
        );
        assertThat(workspaceUseCase.createWorkspaceForOwner(
                idempotencyKey,
                account,
                "박민서",
                command
        )).isEqualTo(created);

        jdbcTemplate.update(
                "UPDATE members SET deactivated_at = ? WHERE id = UUID_TO_BIN(?)",
                FIXED_INSTANT,
                ownerMemberId
        );
        assertThat(workspaceUseCase.createWorkspaceForOwner(
                idempotencyKey,
                account,
                "박민서",
                command
        )).isEqualTo(created);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM teams WHERE idempotency_key_hash IS NOT NULL "
                        + "AND name = ?",
                Integer.class,
                "OWNER 변경 뒤 재생 스터디"
        )).isEqualTo(1);
    }

    @DisplayName("로그인 생성 멱등 키는 계정과 OWNER 선택 및 레거시 생성 방식까지 결속한다")
    @Test
    void bindsOwnedWorkspaceIdempotencyToAccountOwnerAndMode() {
        UUID firstAccountId = UUID.fromString("81000000-0000-4000-8000-000000000002");
        UUID secondAccountId = UUID.fromString("81000000-0000-4000-8000-000000000003");
        jdbcTemplate.update(
                "INSERT INTO user_accounts (id, created_at) VALUES "
                        + "(UUID_TO_BIN(?), ?), (UUID_TO_BIN(?), ?)",
                firstAccountId.toString(),
                FIXED_INSTANT.minusSeconds(60),
                secondAccountId.toString(),
                FIXED_INSTANT.minusSeconds(60)
        );
        String idempotencyKey = "workspace-owner-idempotency-scope-0001";
        CreateWorkspaceCommand command = new CreateWorkspaceCommand(
                "OWNER 멱등 범위 스터디",
                "파일럿 시즌",
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 10, 31),
                List.of("박민서", "김준호")
        );

        workspaceUseCase.createWorkspaceForOwner(
                idempotencyKey,
                new AuthenticatedAccount(firstAccountId),
                "박민서",
                command
        );

        assertThatThrownBy(() -> workspaceUseCase.createWorkspaceForOwner(
                idempotencyKey,
                new AuthenticatedAccount(secondAccountId),
                "박민서",
                command
        )).isInstanceOf(IdempotencyKeyReusedException.class);
        assertThatThrownBy(() -> workspaceUseCase.createWorkspaceForOwner(
                idempotencyKey,
                new AuthenticatedAccount(firstAccountId),
                "김준호",
                command
        )).isInstanceOf(IdempotencyKeyReusedException.class);
        assertThatThrownBy(() -> workspaceUseCase.createWorkspace(
                idempotencyKey,
                CREATION_KEY,
                command
        )).isInstanceOf(IdempotencyKeyReusedException.class);
    }

    @DisplayName("로그인 계정이나 OWNER 선택이 유효하지 않으면 워크스페이스 전체를 롤백한다")
    @Test
    void rollsBackOwnedWorkspaceWhenAccountOrOwnerIsInvalid() {
        int teamsBefore = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM teams", Integer.class);
        int membersBefore = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM members", Integer.class);
        UUID missingAccountId = UUID.fromString("81000000-0000-4000-8000-000000000004");
        CreateWorkspaceCommand command = new CreateWorkspaceCommand(
                "OWNER 롤백 스터디",
                "파일럿 시즌",
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 10, 31),
                List.of("박민서", "김준호")
        );

        assertThatThrownBy(() -> workspaceUseCase.createWorkspaceForOwner(
                "workspace-owner-missing-account-00001",
                new AuthenticatedAccount(missingAccountId),
                "박민서",
                command
        )).isInstanceOfSatisfying(
                IdentityNotFoundException.class,
                exception -> assertThat(exception.getCode()).isEqualTo("ACCOUNT_NOT_FOUND")
        );
        assertThatThrownBy(() -> workspaceUseCase.createWorkspaceForOwner(
                "workspace-owner-missing-member-000001",
                new AuthenticatedAccount(missingAccountId),
                "최유진",
                command
        )).isInstanceOfSatisfying(
                DomainValidationException.class,
                exception -> assertThat(exception.getMessage())
                        .isEqualTo("OWNER 구성원은 초기 구성원 명단에서 선택해야 합니다")
        );

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM teams", Integer.class))
                .isEqualTo(teamsBefore);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM members", Integer.class))
                .isEqualTo(membersBefore);
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

    @DisplayName("대소문자가 다른 초기 구성원 이름은 Java의 정확한 비교 의미대로 함께 저장한다")
    @Test
    void keepsCaseDistinctInitialMemberNames() {
        CreatedWorkspaceResult created = workspaceUseCase.createWorkspace(
                "workspace-initial-member-constraint-0001",
                CREATION_KEY,
                new CreateWorkspaceCommand(
                        "초기 구성원 정확 비교 스터디",
                        "파일럿 시즌",
                        LocalDate.of(2026, 7, 21),
                        LocalDate.of(2026, 8, 31),
                        List.of("Alice", "alice")
                )
        );

        WorkspaceResult workspace = workspaceUseCase.getWorkspace(
                created.teamId(),
                created.seasonId(),
                created.accessKey()
        );
        assertThat(workspace.members())
                .extracting(MemberResult::name)
                .containsExactlyInAnyOrder("Alice", "alice");
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
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
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

    @DisplayName("결정과 바통은 내용을 정정하고 보관했다가 원래 상태로 복원한다")
    @Test
    void revisesArchivesAndRestoresDecisionAndHandoffItem() {
        CreatedWorkspaceResult created = workspaceUseCase.createWorkspace(
                "workspace-record-revision-lifecycle-001",
                CREATION_KEY,
                new CreateWorkspaceCommand(
                        "기록 정정 스터디",
                        "파일럿 시즌",
                        LocalDate.of(2026, 7, 21),
                        LocalDate.of(2026, 8, 31),
                        List.of("박민서", "김준호")
                )
        );
        WorkspaceResult initial = workspaceUseCase.getWorkspace(
                created.teamId(),
                created.seasonId(),
                created.accessKey()
        );
        MemberResult minseo = memberNamed(initial, "박민서");
        MemberResult junho = memberNamed(initial, "김준호");
        RoleResult facilitator = workspaceUseCase.createRole(
                created.teamId(),
                created.seasonId(),
                contentIdempotencyKey("record-revision-facilitator"),
                created.accessKey(),
                new CreateRoleCommand(
                        "진행자", "모임을 진행합니다", minseo.id(), null, null, null, List.of(), null)
        );
        RoleResult recorder = workspaceUseCase.createRole(
                created.teamId(),
                created.seasonId(),
                contentIdempotencyKey("record-revision-recorder"),
                created.accessKey(),
                new CreateRoleCommand(
                        "기록자", "결정과 바통을 정리합니다", junho.id(), null, null, null, List.of(), null)
        );
        String decisionKey = contentIdempotencyKey("record-revision-decision");
        String handoffKey = contentIdempotencyKey("record-revision-handoff");
        DecisionResult decision = workspaceUseCase.createDecision(
                created.teamId(),
                created.seasonId(),
                decisionKey,
                created.accessKey(),
                new CreateDecisionCommand(
                        "질문은 당일 마감한다",
                        "처음 정한 규칙입니다",
                        "",
                        minseo.id(),
                        List.of(facilitator.id())
                )
        );
        HandoffItemResult handoffItem = workspaceUseCase.createHandoffItem(
                created.teamId(),
                created.seasonId(),
                handoffKey,
                created.accessKey(),
                new CreateHandoffItemCommand(
                        facilitator.id(),
                        "질문 문서 권한 넘기기",
                        HandoffCategory.RESOURCE
                )
        );
        workspaceUseCase.updateHandoffItemCompletion(
                created.teamId(),
                created.seasonId(),
                handoffItem.id(),
                created.accessKey(),
                true
        );

        DecisionResult revisedDecision = workspaceUseCase.updateDecision(
                created.teamId(),
                created.seasonId(),
                decision.id(),
                created.accessKey(),
                new UpdateDecisionCommand(
                        "질문은 모임 전날 마감한다",
                        "진행자의 준비 시간을 확보합니다",
                        "당일에도 받는 방안을 검토했습니다",
                        junho.id(),
                        List.of(recorder.id(), facilitator.id())
                )
        );
        HandoffItemResult revisedHandoffItem = workspaceUseCase.updateHandoffItem(
                created.teamId(),
                created.seasonId(),
                handoffItem.id(),
                created.accessKey(),
                new UpdateHandoffItemCommand(
                        recorder.id(),
                        "결정 문서 편집 권한 넘기기",
                        HandoffCategory.RESPONSIBILITY
                )
        );

        assertThat(revisedDecision.createdAt()).isEqualTo(decision.createdAt());
        assertThat(revisedDecision.authorMemberId()).isEqualTo(junho.id());
        assertThat(revisedDecision.authorName()).isEqualTo("김준호");
        assertThat(revisedDecision.roleIds()).containsExactly(recorder.id(), facilitator.id());
        assertThat(revisedHandoffItem.completed()).isTrue();
        assertThat(revisedHandoffItem.roleId()).isEqualTo(recorder.id());

        DecisionResult archivedDecision = workspaceUseCase.updateDecisionArchive(
                created.teamId(),
                created.seasonId(),
                decision.id(),
                created.accessKey(),
                true
        );
        HandoffItemResult archivedHandoffItem = workspaceUseCase.updateHandoffItemArchive(
                created.teamId(),
                created.seasonId(),
                handoffItem.id(),
                created.accessKey(),
                true
        );
        assertThat(archivedDecision.archivedAt()).isEqualTo(FIXED_INSTANT);
        assertThat(archivedHandoffItem.archivedAt()).isEqualTo(FIXED_INSTANT);

        WorkspaceResult archivedProjection = workspaceUseCase.getWorkspace(
                created.teamId(),
                created.seasonId(),
                created.accessKey()
        );
        assertThat(archivedProjection.decisions())
                .filteredOn(item -> item.id().equals(decision.id()))
                .singleElement()
                .satisfies(item -> assertThat(item.archivedAt()).isEqualTo(FIXED_INSTANT));
        assertThat(archivedProjection.handoffItems())
                .filteredOn(item -> item.id().equals(handoffItem.id()))
                .singleElement()
                .satisfies(item -> assertThat(item.archivedAt()).isEqualTo(FIXED_INSTANT));

        assertThat(workspaceUseCase.createDecision(
                created.teamId(),
                created.seasonId(),
                decisionKey,
                created.accessKey(),
                new CreateDecisionCommand(
                        "질문은 당일 마감한다",
                        "처음 정한 규칙입니다",
                        "",
                        minseo.id(),
                        List.of(facilitator.id())
                )
        )).isEqualTo(archivedDecision);
        assertThat(workspaceUseCase.createHandoffItem(
                created.teamId(),
                created.seasonId(),
                handoffKey,
                created.accessKey(),
                new CreateHandoffItemCommand(
                        facilitator.id(),
                        "질문 문서 권한 넘기기",
                        HandoffCategory.RESOURCE
                )
        )).isEqualTo(archivedHandoffItem);

        assertThatThrownBy(() -> workspaceUseCase.updateDecision(
                created.teamId(),
                created.seasonId(),
                decision.id(),
                created.accessKey(),
                new UpdateDecisionCommand(
                        "보관된 결정 수정",
                        "복원 전에는 수정할 수 없습니다",
                        "",
                        minseo.id(),
                        List.of(facilitator.id())
                )
        )).isInstanceOfSatisfying(
                WorkspaceNotFoundException.class,
                exception -> assertThat(exception.getCode()).isEqualTo("DECISION_NOT_FOUND")
        );
        assertThatThrownBy(() -> workspaceUseCase.updateHandoffItemCompletion(
                created.teamId(),
                created.seasonId(),
                handoffItem.id(),
                created.accessKey(),
                false
        )).isInstanceOfSatisfying(
                WorkspaceNotFoundException.class,
                exception -> assertThat(exception.getCode()).isEqualTo("HANDOFF_ITEM_NOT_FOUND")
        );

        DecisionResult restoredDecision = workspaceUseCase.updateDecisionArchive(
                created.teamId(),
                created.seasonId(),
                decision.id(),
                created.accessKey(),
                false
        );
        HandoffItemResult restoredHandoffItem = workspaceUseCase.updateHandoffItemArchive(
                created.teamId(),
                created.seasonId(),
                handoffItem.id(),
                created.accessKey(),
                false
        );
        assertThat(restoredDecision.archivedAt()).isNull();
        assertThat(restoredHandoffItem.archivedAt()).isNull();
        assertThat(restoredHandoffItem.completed()).isTrue();
    }

    @DisplayName("결정과 바통 수정은 시즌과 팀 소유권을 모두 지킨다")
    @Test
    void enforcesRecordRevisionOwnershipBoundaries() {
        CreatedWorkspaceResult primary = workspaceUseCase.createWorkspace(
                "workspace-record-ownership-primary-001",
                CREATION_KEY,
                new CreateWorkspaceCommand(
                        "기록 소유권 스터디",
                        "여름 시즌",
                        LocalDate.of(2026, 7, 1),
                        LocalDate.of(2026, 8, 31),
                        List.of("박민서")
                )
        );
        WorkspaceResult primaryWorkspace = workspaceUseCase.getWorkspace(
                primary.teamId(),
                primary.seasonId(),
                primary.accessKey()
        );
        MemberResult primaryMember = primaryWorkspace.members().getFirst();
        RoleResult primaryRole = workspaceUseCase.createRole(
                primary.teamId(),
                primary.seasonId(),
                contentIdempotencyKey("record-ownership-primary-role"),
                primary.accessKey(),
                new CreateRoleCommand(
                        "기록자", "팀의 기록을 관리합니다",
                        primaryMember.id(), null, null, null, List.of(), null)
        );
        DecisionResult decision = workspaceUseCase.createDecision(
                primary.teamId(),
                primary.seasonId(),
                contentIdempotencyKey("record-ownership-decision"),
                primary.accessKey(),
                new CreateDecisionCommand(
                        "질문은 전날 마감한다",
                        "준비 시간을 확보합니다",
                        "",
                        primaryMember.id(),
                        List.of(primaryRole.id())
                )
        );
        HandoffItemResult handoffItem = workspaceUseCase.createHandoffItem(
                primary.teamId(),
                primary.seasonId(),
                contentIdempotencyKey("record-ownership-handoff"),
                primary.accessKey(),
                new CreateHandoffItemCommand(
                        primaryRole.id(),
                        "질문 문서 권한 넘기기",
                        HandoffCategory.RESOURCE
                )
        );

        UUID anotherSeasonId = UUID.randomUUID();
        workspaceUseCase.updateSeasonEnding(
                primary.teamId(),
                primary.seasonId(),
                primary.accessKey(),
                true
        );
        workspaceRepository.saveSeason(Season.createSuccessor(
                anotherSeasonId,
                primary.teamId(),
                primary.seasonId(),
                "가을 시즌",
                LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 10, 31)
        ));

        assertThatThrownBy(() -> workspaceUseCase.updateDecision(
                primary.teamId(),
                anotherSeasonId,
                decision.id(),
                primary.accessKey(),
                new UpdateDecisionCommand(
                        decision.title(),
                        decision.reason(),
                        decision.alternative(),
                        primaryMember.id(),
                        List.of(primaryRole.id())
                )
        )).isInstanceOfSatisfying(
                WorkspaceNotFoundException.class,
                exception -> assertThat(exception.getCode()).isEqualTo("DECISION_NOT_FOUND")
        );

        assertThatThrownBy(() -> workspaceUseCase.updateHandoffItem(
                primary.teamId(),
                anotherSeasonId,
                handoffItem.id(),
                primary.accessKey(),
                new UpdateHandoffItemCommand(
                        primaryRole.id(),
                        "다음 시즌에도 이어지는 질문 문서 권한",
                        HandoffCategory.RESOURCE
                )
        )).isInstanceOfSatisfying(
                WorkspaceNotFoundException.class,
                exception -> assertThat(exception.getCode()).isEqualTo("HANDOFF_ITEM_NOT_FOUND")
        );

        CreatedWorkspaceResult secondary = workspaceUseCase.createWorkspace(
                "workspace-record-ownership-secondary-001",
                CREATION_KEY,
                new CreateWorkspaceCommand(
                        "다른 기록 스터디",
                        "파일럿 시즌",
                        LocalDate.of(2026, 7, 1),
                        LocalDate.of(2026, 8, 31),
                        List.of("김준호")
                )
        );
        MemberResult secondaryMember = workspaceUseCase.getWorkspace(
                secondary.teamId(),
                secondary.seasonId(),
                secondary.accessKey()
        ).members().getFirst();
        RoleResult secondaryRole = workspaceUseCase.createRole(
                secondary.teamId(),
                secondary.seasonId(),
                contentIdempotencyKey("record-ownership-secondary-role"),
                secondary.accessKey(),
                new CreateRoleCommand(
                        "외부 기록자", "다른 팀의 기록을 관리합니다",
                        secondaryMember.id(), null, null, null, List.of(), null)
        );

        assertThatThrownBy(() -> workspaceUseCase.updateDecisionArchive(
                secondary.teamId(),
                secondary.seasonId(),
                decision.id(),
                secondary.accessKey(),
                true
        )).isInstanceOfSatisfying(
                WorkspaceNotFoundException.class,
                exception -> assertThat(exception.getCode()).isEqualTo("DECISION_NOT_FOUND")
        );
        assertThatThrownBy(() -> workspaceUseCase.updateHandoffItem(
                secondary.teamId(),
                secondary.seasonId(),
                handoffItem.id(),
                secondary.accessKey(),
                new UpdateHandoffItemCommand(
                        secondaryRole.id(),
                        "다른 팀이 바꾸려는 바통",
                        HandoffCategory.ADVICE
                )
        )).isInstanceOfSatisfying(
                WorkspaceNotFoundException.class,
                exception -> assertThat(exception.getCode()).isEqualTo("HANDOFF_ITEM_NOT_FOUND")
        );
        assertThatThrownBy(() -> workspaceUseCase.updateDecision(
                primary.teamId(),
                primary.seasonId(),
                decision.id(),
                primary.accessKey(),
                new UpdateDecisionCommand(
                        decision.title(),
                        decision.reason(),
                        decision.alternative(),
                        secondaryMember.id(),
                        List.of(primaryRole.id())
                )
        )).isInstanceOf(SeasonEndedException.class);
        assertThatThrownBy(() -> workspaceUseCase.updateDecision(
                primary.teamId(),
                primary.seasonId(),
                decision.id(),
                primary.accessKey(),
                new UpdateDecisionCommand(
                        decision.title(),
                        decision.reason(),
                        decision.alternative(),
                        primaryMember.id(),
                        List.of(secondaryRole.id())
                )
        )).isInstanceOf(SeasonEndedException.class);
        assertThatThrownBy(() -> workspaceUseCase.updateHandoffItem(
                primary.teamId(),
                primary.seasonId(),
                handoffItem.id(),
                primary.accessKey(),
                new UpdateHandoffItemCommand(
                        secondaryRole.id(),
                        "외부 역할로 옮기려는 바통",
                        HandoffCategory.ADVICE
                )
        )).isInstanceOf(SeasonEndedException.class);
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

    @DisplayName("이전 접근 키로 시작한 쓰기가 끝날 때까지 키 회전은 기다리고 이후에는 이전 키를 거절한다")
    @Test
    void serializesWorkspaceMutationBeforeAccessKeyRotation() throws Exception {
        CreatedWorkspaceResult created = workspaceUseCase.createWorkspace(
                "workspace-idempotency-mutation-key-lock-01",
                CREATION_KEY,
                new CreateWorkspaceCommand(
                        "접근 키 쓰기 직렬화 스터디",
                        "파일럿 시즌",
                        LocalDate.of(2026, 7, 21),
                        LocalDate.of(2026, 8, 31),
                        List.of("박민서")
                )
        );
        RoleResult role = workspaceUseCase.createRole(
                created.teamId(),
                created.seasonId(),
                contentIdempotencyKey("mutation-key-lock-role"),
                created.accessKey(),
                new CreateRoleCommand(
                        "진행자", "모임을 진행합니다", null, null, null, null, List.of(), null)
        );
        CountDownLatch mutationHasSharedTeamLock = new CountDownLatch(1);
        CountDownLatch rotationAttemptsTeamUpdate = new CountDownLatch(1);
        CountDownLatch rotationCompletesTeamUpdate = new CountDownLatch(1);
        CountDownLatch allowMutationToCommit = new CountDownLatch(1);
        WorkspaceRepository coordinatedRepository = mock(
                WorkspaceRepository.class,
                delegatesTo(workspaceRepository)
        );
        doAnswer(invocation -> {
            Object lockedTeam = workspaceRepository.findTeamByIdWithSharedLock(
                    invocation.getArgument(0)
            );
            mutationHasSharedTeamLock.countDown();
            allowMutationToCommit.await(10, TimeUnit.SECONDS);
            return lockedTeam;
        }).when(coordinatedRepository).findTeamByIdWithSharedLock(any(UUID.class));
        doAnswer(invocation -> {
            rotationAttemptsTeamUpdate.countDown();
            Object savedTeam = workspaceRepository.saveTeam(invocation.getArgument(0));
            rotationCompletesTeamUpdate.countDown();
            return savedTeam;
        }).when(coordinatedRepository).saveTeam(any(Team.class));
        WorkspaceService coordinatedService = new WorkspaceService(
                coordinatedRepository,
                Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC),
                CREATION_KEY,
                RECOVERY_KEY
        );
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<RoleResult> mutation = executor.submit(() ->
                    transactionTemplate.execute(status -> coordinatedService.updateRole(
                            created.teamId(),
                            created.seasonId(),
                            role.id(),
                            created.accessKey(),
                            new UpdateRoleCommand(
                                    "메인 진행자",
                                    "키 회전 전에 시작한 변경을 완료합니다",
                                    null,
                                    null,
                                    null,
                                    null,
                                    List.of("모임 진행"),
                                    null
                            )
                    )));
            assertThat(mutationHasSharedTeamLock.await(10, TimeUnit.SECONDS)).isTrue();
            Future<WorkspaceUseCase.AccessKeyResult> rotation = executor.submit(() ->
                    transactionTemplate.execute(status -> coordinatedService.rotateAccessKey(
                            created.teamId(),
                            created.seasonId(),
                            "workspace-mutation-key-lock-rotation-01",
                            created.accessKey()
                    )));
            assertThat(rotationAttemptsTeamUpdate.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(rotationCompletesTeamUpdate.await(1, TimeUnit.SECONDS)).isFalse();

            allowMutationToCommit.countDown();

            assertThat(mutation.get(30, TimeUnit.SECONDS).name()).isEqualTo("메인 진행자");
            WorkspaceUseCase.AccessKeyResult rotated = rotation.get(30, TimeUnit.SECONDS);
            assertThat(rotationCompletesTeamUpdate.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> workspaceUseCase.updateRole(
                    created.teamId(),
                    created.seasonId(),
                    role.id(),
                    created.accessKey(),
                    new UpdateRoleCommand(
                            "폐기된 키 수정",
                            role.purpose(),
                            null,
                            null,
                            null,
                            null,
                            List.of(),
                            null
                    )
            )).isInstanceOf(WorkspaceAccessDeniedException.class);
            assertThat(workspaceUseCase.getWorkspace(
                    created.teamId(),
                    created.seasonId(),
                    rotated.accessKey()
            ).roles()).filteredOn(savedRole -> savedRole.id().equals(role.id()))
                    .singleElement()
                    .satisfies(savedRole -> assertThat(savedRole.name()).isEqualTo("메인 진행자"));
        } finally {
            allowMutationToCommit.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @DisplayName("접근 키 복구가 먼저 팀을 갱신하면 이전 키 쓰기는 커밋을 기다린 뒤 거절된다")
    @Test
    void rejectsOldKeyMutationAfterAccessKeyRecoveryCommits() throws Exception {
        CreatedWorkspaceResult created = workspaceUseCase.createWorkspace(
                "workspace-idempotency-recovery-mutation-lock-1",
                CREATION_KEY,
                new CreateWorkspaceCommand(
                        "접근 키 복구 직렬화 스터디",
                        "파일럿 시즌",
                        LocalDate.of(2026, 7, 21),
                        LocalDate.of(2026, 8, 31),
                        List.of("박민서")
                )
        );
        RoleResult role = workspaceUseCase.createRole(
                created.teamId(),
                created.seasonId(),
                contentIdempotencyKey("recovery-mutation-lock-role"),
                created.accessKey(),
                new CreateRoleCommand(
                        "진행자", "모임을 진행합니다", null, null, null, null, List.of(), null)
        );
        CountDownLatch recoveryHasTeamUpdate = new CountDownLatch(1);
        CountDownLatch mutationAttemptsSharedTeamLock = new CountDownLatch(1);
        CountDownLatch mutationHasSharedTeamLock = new CountDownLatch(1);
        CountDownLatch allowRecoveryToCommit = new CountDownLatch(1);
        WorkspaceRepository coordinatedRepository = mock(
                WorkspaceRepository.class,
                delegatesTo(workspaceRepository)
        );
        doAnswer(invocation -> {
            Object savedTeam = workspaceRepository.saveTeam(invocation.getArgument(0));
            recoveryHasTeamUpdate.countDown();
            allowRecoveryToCommit.await(10, TimeUnit.SECONDS);
            return savedTeam;
        }).when(coordinatedRepository).saveTeam(any(Team.class));
        doAnswer(invocation -> {
            mutationAttemptsSharedTeamLock.countDown();
            Object lockedTeam = workspaceRepository.findTeamByIdWithSharedLock(
                    invocation.getArgument(0)
            );
            mutationHasSharedTeamLock.countDown();
            return lockedTeam;
        }).when(coordinatedRepository).findTeamByIdWithSharedLock(any(UUID.class));
        WorkspaceService coordinatedService = new WorkspaceService(
                coordinatedRepository,
                Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC),
                CREATION_KEY,
                RECOVERY_KEY
        );
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<WorkspaceUseCase.AccessKeyResult> recovery = executor.submit(() ->
                    transactionTemplate.execute(status -> coordinatedService.recoverAccessKey(
                            created.teamId(),
                            created.seasonId(),
                            "workspace-recovery-mutation-lock-key-01",
                            RECOVERY_KEY
                    )));
            assertThat(recoveryHasTeamUpdate.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Object> mutation = executor.submit(() -> {
                try {
                    return transactionTemplate.execute(status -> coordinatedService.updateRole(
                            created.teamId(),
                            created.seasonId(),
                            role.id(),
                            created.accessKey(),
                            new UpdateRoleCommand(
                                    "폐기될 키의 수정",
                                    role.purpose(),
                                    null,
                                    null,
                                    null,
                                    null,
                                    List.of(),
                                    null
                            )
                    ));
                } catch (WorkspaceAccessDeniedException exception) {
                    return exception;
                }
            });
            assertThat(mutationAttemptsSharedTeamLock.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(mutationHasSharedTeamLock.await(1, TimeUnit.SECONDS)).isFalse();

            allowRecoveryToCommit.countDown();

            WorkspaceUseCase.AccessKeyResult recovered = recovery.get(30, TimeUnit.SECONDS);
            assertThat(mutation.get(30, TimeUnit.SECONDS))
                    .isInstanceOf(WorkspaceAccessDeniedException.class);
            assertThat(mutationHasSharedTeamLock.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(workspaceUseCase.getWorkspace(
                    created.teamId(),
                    created.seasonId(),
                    recovered.accessKey()
            ).roles()).filteredOn(savedRole -> savedRole.id().equals(role.id()))
                    .singleElement()
                    .satisfies(savedRole -> assertThat(savedRole.name()).isEqualTo("진행자"));
        } finally {
            allowRecoveryToCommit.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @DisplayName("같은 팀의 서로 다른 접근 키 변경이 겹치면 한 요청만 커밋되고 다른 요청은 충돌한다")
    @Test
    void rejectsOverlappingAccessKeyChangesAfterExclusiveLockWait() throws Exception {
        CreatedWorkspaceResult created = workspaceUseCase.createWorkspace(
                "workspace-idempotency-key-change-lock-01",
                CREATION_KEY,
                new CreateWorkspaceCommand(
                        "접근 키 변경 충돌 스터디",
                        "파일럿 시즌",
                        LocalDate.of(2026, 7, 21),
                        LocalDate.of(2026, 8, 31),
                        List.of("박민서")
                )
        );
        CyclicBarrier bothRequestsReadSameTeamVersion = new CyclicBarrier(2);
        WorkspaceRepository coordinatedRepository = mock(
                WorkspaceRepository.class,
                delegatesTo(workspaceRepository)
        );
        doAnswer(invocation -> {
            Object team = workspaceRepository.findTeamById(invocation.getArgument(0));
            bothRequestsReadSameTeamVersion.await(10, TimeUnit.SECONDS);
            return team;
        }).when(coordinatedRepository).findTeamById(created.teamId());
        WorkspaceService coordinatedService = new WorkspaceService(
                coordinatedRepository,
                Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC),
                CREATION_KEY,
                RECOVERY_KEY
        );
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Object> first = executor.submit(() -> runAccessKeyRotationTransaction(
                    transactionTemplate,
                    coordinatedService,
                    created,
                    "workspace-key-change-overlap-first-0001"
            ));
            Future<Object> second = executor.submit(() -> runAccessKeyRotationTransaction(
                    transactionTemplate,
                    coordinatedService,
                    created,
                    "workspace-key-change-overlap-second-001"
            ));
            List<Object> outcomes = List.of(
                    first.get(30, TimeUnit.SECONDS),
                    second.get(30, TimeUnit.SECONDS)
            );

            assertThat(outcomes).filteredOn(WorkspaceUseCase.AccessKeyResult.class::isInstance)
                    .singleElement();
            assertThat(outcomes).filteredOn(WorkspaceAccessKeyConflictException.class::isInstance)
                    .singleElement();
            WorkspaceUseCase.AccessKeyResult committed = outcomes.stream()
                    .filter(WorkspaceUseCase.AccessKeyResult.class::isInstance)
                    .map(WorkspaceUseCase.AccessKeyResult.class::cast)
                    .findFirst()
                    .orElseThrow();
            assertThatThrownBy(() -> workspaceUseCase.getWorkspace(
                    created.teamId(),
                    created.seasonId(),
                    created.accessKey()
            )).isInstanceOf(WorkspaceAccessDeniedException.class);
            assertThat(workspaceUseCase.getWorkspace(
                    created.teamId(),
                    created.seasonId(),
                    committed.accessKey()
            ).team().id()).isEqualTo(created.teamId());
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @DisplayName("같은 버전의 루틴 실행을 읽은 두 저장은 완료 상태를 모두 커밋할 수 없다")
    @Test
    void rejectsStaleRoutineExecutionUpdate() {
        CreatedWorkspaceResult created = workspaceUseCase.createWorkspace(
                "workspace-execution-optimistic-lock-01",
                CREATION_KEY,
                new CreateWorkspaceCommand(
                        "실행 충돌 스터디",
                        "파일럿 시즌",
                        LocalDate.of(2026, 7, 21),
                        LocalDate.of(2026, 8, 31),
                        List.of("박민서")
                )
        );
        RoleResult role = workspaceUseCase.createRole(
                created.teamId(),
                created.seasonId(),
                contentIdempotencyKey("execution-lock-role"),
                created.accessKey(),
                new CreateRoleCommand(
                        "진행자", "모임을 진행합니다", null, null, null, null, List.of(), null)
        );
        workspaceUseCase.createRoutine(
                created.teamId(),
                created.seasonId(),
                contentIdempotencyKey("execution-lock-routine"),
                created.accessKey(),
                new CreateRoutineCommand(
                        "질문 모으기", RoutinePhase.BEFORE, "모임 전", role.id(), "질문을 모읍니다")
        );
        SeasonRoundResult round = workspaceUseCase.createSeasonRound(
                created.teamId(),
                created.seasonId(),
                contentIdempotencyKey("execution-lock-round"),
                created.accessKey(),
                new CreateSeasonRoundCommand("1회차", LocalDate.of(2026, 7, 28))
        );
        UUID executionId = round.routineExecutions().getFirst().id();
        EntityManager firstEntityManager = entityManagerFactory.createEntityManager();
        EntityManager secondEntityManager = entityManagerFactory.createEntityManager();

        try {
            RoutineExecution first = firstEntityManager.find(RoutineExecution.class, executionId);
            RoutineExecution stale = secondEntityManager.find(RoutineExecution.class, executionId);
            firstEntityManager.detach(first);
            secondEntityManager.detach(stale);

            first.updateCompletion(true);
            stale.updateCompletion(true);
            workspaceRepository.saveRoutineExecution(first);

            assertThatThrownBy(() -> workspaceRepository.saveRoutineExecution(stale))
                    .isInstanceOf(WorkspaceContentConflictException.class);
        } finally {
            firstEntityManager.close();
            secondEntityManager.close();
        }
    }

    @DisplayName("같은 회차의 실행 완료 요청은 부모 공유 잠금을 함께 통과하고 실행 버전 충돌을 보존한다")
    @Test
    void rejectsConcurrentRoutineExecutionCompletionThroughServiceWhileSharingRoundLock() throws Exception {
        CreatedWorkspaceResult created = workspaceUseCase.createWorkspace(
                "workspace-execution-shared-round-lock-01",
                CREATION_KEY,
                new CreateWorkspaceCommand(
                        "실행 공유 잠금 스터디",
                        "파일럿 시즌",
                        LocalDate.of(2026, 7, 21),
                        LocalDate.of(2026, 8, 31),
                        List.of("박민서")
                )
        );
        RoleResult role = workspaceUseCase.createRole(
                created.teamId(),
                created.seasonId(),
                contentIdempotencyKey("execution-shared-lock-role"),
                created.accessKey(),
                new CreateRoleCommand(
                        "진행자", "모임을 진행합니다", null, null, null, null, List.of(), null)
        );
        workspaceUseCase.createRoutine(
                created.teamId(),
                created.seasonId(),
                contentIdempotencyKey("execution-shared-lock-routine"),
                created.accessKey(),
                new CreateRoutineCommand(
                        "질문 모으기", RoutinePhase.BEFORE, "모임 전", role.id(), "질문을 모읍니다")
        );
        SeasonRoundResult round = workspaceUseCase.createSeasonRound(
                created.teamId(),
                created.seasonId(),
                contentIdempotencyKey("execution-shared-lock-round"),
                created.accessKey(),
                new CreateSeasonRoundCommand("1회차", LocalDate.of(2026, 7, 28))
        );
        UUID executionId = round.routineExecutions().getFirst().id();
        CyclicBarrier bothRequestsLoadedSameExecution = new CyclicBarrier(2);
        WorkspaceRepository coordinatedRepository = mock(
                WorkspaceRepository.class,
                delegatesTo(workspaceRepository)
        );
        doAnswer(invocation -> {
            Object execution = workspaceRepository.findRoutineExecutionById(invocation.getArgument(0));
            bothRequestsLoadedSameExecution.await(10, TimeUnit.SECONDS);
            return execution;
        }).when(coordinatedRepository).findRoutineExecutionById(executionId);
        WorkspaceService coordinatedService = new WorkspaceService(
                coordinatedRepository,
                Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC),
                CREATION_KEY,
                RECOVERY_KEY
        );
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Object> first = executor.submit(() -> {
                try {
                    return transactionTemplate.execute(status ->
                            coordinatedService.updateRoutineExecutionCompletion(
                                    created.teamId(),
                                    created.seasonId(),
                                    round.id(),
                                    executionId,
                                    created.accessKey(),
                                    true
                            ));
                } catch (WorkspaceContentConflictException exception) {
                    return exception;
                }
            });
            Future<Object> second = executor.submit(() -> {
                try {
                    return transactionTemplate.execute(status ->
                            coordinatedService.updateRoutineExecutionCompletion(
                                    created.teamId(),
                                    created.seasonId(),
                                    round.id(),
                                    executionId,
                                    created.accessKey(),
                                    true
                            ));
                } catch (WorkspaceContentConflictException exception) {
                    return exception;
                }
            });

            List<Object> results = List.of(
                    first.get(30, TimeUnit.SECONDS),
                    second.get(30, TimeUnit.SECONDS)
            );
            assertThat(results).filteredOn(RoutineExecutionResult.class::isInstance)
                    .singleElement()
                    .satisfies(result -> assertThat((RoutineExecutionResult) result)
                            .extracting(RoutineExecutionResult::status)
                            .isEqualTo(RoutineStatus.DONE));
            assertThat(results).filteredOn(WorkspaceContentConflictException.class::isInstance)
                    .singleElement();
            assertThat(workspaceUseCase.getWorkspace(
                    created.teamId(),
                    created.seasonId(),
                    created.accessKey()
            ).rounds()).filteredOn(savedRound -> savedRound.id().equals(round.id()))
                    .singleElement()
                    .satisfies(savedRound -> assertThat(savedRound.routineExecutions())
                            .singleElement()
                            .extracting(RoutineExecutionResult::status)
                            .isEqualTo(RoutineStatus.DONE));
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @DisplayName("같은 버전의 회차를 읽은 두 저장은 메타데이터 변경을 모두 커밋할 수 없다")
    @Test
    void rejectsStaleSeasonRoundUpdate() {
        CreatedWorkspaceResult created = workspaceUseCase.createWorkspace(
                "workspace-round-optimistic-lock-0001",
                CREATION_KEY,
                new CreateWorkspaceCommand(
                        "회차 충돌 스터디",
                        "파일럿 시즌",
                        LocalDate.of(2026, 7, 21),
                        LocalDate.of(2026, 8, 31),
                        List.of("박민서")
                )
        );
        SeasonRoundResult round = workspaceUseCase.createSeasonRound(
                created.teamId(),
                created.seasonId(),
                contentIdempotencyKey("round-lock-round"),
                created.accessKey(),
                new CreateSeasonRoundCommand("1회차", LocalDate.of(2026, 7, 28))
        );
        EntityManager firstEntityManager = entityManagerFactory.createEntityManager();
        EntityManager secondEntityManager = entityManagerFactory.createEntityManager();

        try {
            SeasonRound first = firstEntityManager.find(SeasonRound.class, round.id());
            SeasonRound stale = secondEntityManager.find(SeasonRound.class, round.id());
            firstEntityManager.detach(first);
            secondEntityManager.detach(stale);

            first.update("첫 모임", LocalDate.of(2026, 7, 29));
            stale.update("오래된 수정", LocalDate.of(2026, 7, 30));
            workspaceRepository.saveSeasonRound(first);

            assertThatThrownBy(() -> workspaceRepository.saveSeasonRound(stale))
                    .isInstanceOf(WorkspaceContentConflictException.class);
        } finally {
            firstEntityManager.close();
            secondEntityManager.close();
        }
    }

    @DisplayName("실행 완료가 부모 공유 잠금을 보유하면 회차 날짜 변경은 커밋까지 기다린 뒤 완료 상태와 새 마감을 함께 보존한다")
    @Test
    void serializesRoutineExecutionCompletionBeforeSeasonRoundReschedule() throws Exception {
        CreatedWorkspaceResult created = workspaceUseCase.createWorkspace(
                "workspace-round-completion-reschedule-lock-1",
                CREATION_KEY,
                new CreateWorkspaceCommand(
                        "회차 날짜 직렬화 스터디",
                        "파일럿 시즌",
                        LocalDate.of(2026, 7, 21),
                        LocalDate.of(2026, 8, 31),
                        List.of("박민서")
                )
        );
        RoleResult role = workspaceUseCase.createRole(
                created.teamId(),
                created.seasonId(),
                contentIdempotencyKey("round-reschedule-lock-role"),
                created.accessKey(),
                new CreateRoleCommand(
                        "진행자", "모임을 진행합니다", null, null, null, null, List.of(), null)
        );
        workspaceUseCase.createRoutine(
                created.teamId(),
                created.seasonId(),
                contentIdempotencyKey("round-reschedule-lock-routine"),
                created.accessKey(),
                new CreateRoutineCommand(
                        "질문 모으기",
                        RoutinePhase.BEFORE,
                        "모임 하루 전",
                        role.id(),
                        "질문을 모읍니다",
                        -1,
                        LocalTime.of(22, 0)
                )
        );
        SeasonRoundResult round = workspaceUseCase.createSeasonRound(
                created.teamId(),
                created.seasonId(),
                contentIdempotencyKey("round-reschedule-lock-round"),
                created.accessKey(),
                new CreateSeasonRoundCommand("1회차", LocalDate.of(2026, 7, 28))
        );
        UUID executionId = round.routineExecutions().getFirst().id();
        CountDownLatch completionHasParentLock = new CountDownLatch(1);
        CountDownLatch rescheduleAttemptsParentLock = new CountDownLatch(1);
        CountDownLatch rescheduleHasParentLock = new CountDownLatch(1);
        CountDownLatch allowCompletionToCommit = new CountDownLatch(1);
        WorkspaceRepository coordinatedRepository = mock(
                WorkspaceRepository.class,
                delegatesTo(workspaceRepository)
        );
        doAnswer(invocation -> {
            Object lockedRound = workspaceRepository.findSeasonRoundBySeasonIdAndIdWithSharedLock(
                    invocation.getArgument(0),
                    invocation.getArgument(1)
            );
            completionHasParentLock.countDown();
            allowCompletionToCommit.await(10, TimeUnit.SECONDS);
            return lockedRound;
        }).when(coordinatedRepository).findSeasonRoundBySeasonIdAndIdWithSharedLock(
                any(UUID.class),
                any(UUID.class)
        );
        doAnswer(invocation -> {
            rescheduleAttemptsParentLock.countDown();
            Object lockedRound = workspaceRepository.findSeasonRoundBySeasonIdAndIdForUpdate(
                    invocation.getArgument(0),
                    invocation.getArgument(1)
            );
            rescheduleHasParentLock.countDown();
            return lockedRound;
        }).when(coordinatedRepository).findSeasonRoundBySeasonIdAndIdForUpdate(
                any(UUID.class),
                any(UUID.class)
        );
        WorkspaceService coordinatedService = new WorkspaceService(
                coordinatedRepository,
                Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC),
                CREATION_KEY,
                RECOVERY_KEY
        );
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<RoutineExecutionResult> completion = executor.submit(() ->
                    transactionTemplate.execute(status ->
                            coordinatedService.updateRoutineExecutionCompletion(
                                    created.teamId(),
                                    created.seasonId(),
                                    round.id(),
                                    executionId,
                                    created.accessKey(),
                                    true
                            )));
            assertThat(completionHasParentLock.await(10, TimeUnit.SECONDS)).isTrue();

            Future<SeasonRoundResult> rescheduled = executor.submit(() ->
                    transactionTemplate.execute(status -> coordinatedService.updateSeasonRound(
                            created.teamId(),
                            created.seasonId(),
                            round.id(),
                            created.accessKey(),
                            new UpdateSeasonRoundCommand(
                                    "2회차",
                                    LocalDate.of(2026, 7, 30)
                            )
                    )));
            assertThat(rescheduleAttemptsParentLock.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(rescheduleHasParentLock.await(250, TimeUnit.MILLISECONDS)).isFalse();

            allowCompletionToCommit.countDown();

            assertThat(completion.get(30, TimeUnit.SECONDS).status()).isEqualTo(RoutineStatus.DONE);
            assertThat(rescheduleHasParentLock.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(rescheduled.get(30, TimeUnit.SECONDS))
                    .satisfies(updatedRound -> {
                        assertThat(updatedRound.name()).isEqualTo("2회차");
                        assertThat(updatedRound.meetingDate()).isEqualTo(LocalDate.of(2026, 7, 30));
                        assertThat(updatedRound.routineExecutions()).singleElement()
                                .satisfies(execution -> {
                                    assertThat(execution.status()).isEqualTo(RoutineStatus.DONE);
                                    assertThat(execution.deadlineAt())
                                            .isEqualTo(Instant.parse("2026-07-29T13:00:00Z"));
                                });
                    });
        } finally {
            allowCompletionToCommit.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @DisplayName("회차 보관이 먼저 잠기면 동시에 시작한 실행 완료 변경은 보관 커밋 뒤 거절된다")
    @Test
    void serializesSeasonRoundArchiveBeforeRoutineExecutionCompletion() throws Exception {
        CreatedWorkspaceResult created = workspaceUseCase.createWorkspace(
                "workspace-round-archive-completion-lock-1",
                CREATION_KEY,
                new CreateWorkspaceCommand(
                        "회차 보관 직렬화 스터디",
                        "파일럿 시즌",
                        LocalDate.of(2026, 7, 21),
                        LocalDate.of(2026, 8, 31),
                        List.of("박민서")
                )
        );
        RoleResult role = workspaceUseCase.createRole(
                created.teamId(),
                created.seasonId(),
                contentIdempotencyKey("round-archive-lock-role"),
                created.accessKey(),
                new CreateRoleCommand(
                        "진행자", "모임을 진행합니다", null, null, null, null, List.of(), null)
        );
        workspaceUseCase.createRoutine(
                created.teamId(),
                created.seasonId(),
                contentIdempotencyKey("round-archive-lock-routine"),
                created.accessKey(),
                new CreateRoutineCommand(
                        "질문 모으기", RoutinePhase.BEFORE, "모임 전", role.id(), "질문을 모읍니다")
        );
        SeasonRoundResult round = workspaceUseCase.createSeasonRound(
                created.teamId(),
                created.seasonId(),
                contentIdempotencyKey("round-archive-lock-round"),
                created.accessKey(),
                new CreateSeasonRoundCommand("1회차", LocalDate.of(2026, 7, 28))
        );
        UUID executionId = round.routineExecutions().getFirst().id();
        CountDownLatch archiveHasParentLock = new CountDownLatch(1);
        CountDownLatch completionAttemptsParentLock = new CountDownLatch(1);
        CountDownLatch allowArchiveToCommit = new CountDownLatch(1);
        WorkspaceRepository coordinatedRepository = mock(
                WorkspaceRepository.class,
                delegatesTo(workspaceRepository)
        );
        doAnswer(invocation -> {
            UUID seasonId = invocation.getArgument(0);
            UUID roundId = invocation.getArgument(1);
            Object lockedRound = workspaceRepository.findSeasonRoundBySeasonIdAndIdForUpdate(
                    seasonId,
                    roundId
            );
            archiveHasParentLock.countDown();
            allowArchiveToCommit.await(10, TimeUnit.SECONDS);
            return lockedRound;
        }).when(coordinatedRepository).findSeasonRoundBySeasonIdAndIdForUpdate(
                any(UUID.class),
                any(UUID.class)
        );
        doAnswer(invocation -> {
            completionAttemptsParentLock.countDown();
            return workspaceRepository.findSeasonRoundBySeasonIdAndIdWithSharedLock(
                    invocation.getArgument(0),
                    invocation.getArgument(1)
            );
        }).when(coordinatedRepository).findSeasonRoundBySeasonIdAndIdWithSharedLock(
                any(UUID.class),
                any(UUID.class)
        );
        WorkspaceService coordinatedService = new WorkspaceService(
                coordinatedRepository,
                Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC),
                CREATION_KEY,
                RECOVERY_KEY
        );
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<SeasonRoundResult> archived = executor.submit(() ->
                    transactionTemplate.execute(status -> coordinatedService.updateSeasonRoundArchive(
                            created.teamId(),
                            created.seasonId(),
                            round.id(),
                            created.accessKey(),
                            true
                    )));
            assertThat(archiveHasParentLock.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Object> completion = executor.submit(() -> {
                try {
                    return transactionTemplate.execute(status ->
                            coordinatedService.updateRoutineExecutionCompletion(
                                    created.teamId(),
                                    created.seasonId(),
                                    round.id(),
                                    executionId,
                                    created.accessKey(),
                                    true
                            ));
                } catch (WorkspaceNotFoundException exception) {
                    return exception;
                }
            });
            assertThat(completionAttemptsParentLock.await(10, TimeUnit.SECONDS)).isTrue();

            allowArchiveToCommit.countDown();

            assertThat(archived.get(30, TimeUnit.SECONDS).archivedAt()).isEqualTo(FIXED_INSTANT);
            assertThat(completion.get(30, TimeUnit.SECONDS))
                    .isInstanceOfSatisfying(
                            WorkspaceNotFoundException.class,
                            exception -> assertThat(exception.getCode())
                                    .isEqualTo("SEASON_ROUND_NOT_FOUND")
                    );
            assertThat(workspaceUseCase.getWorkspace(
                    created.teamId(),
                    created.seasonId(),
                    created.accessKey()
            ).rounds()).filteredOn(savedRound -> savedRound.id().equals(round.id()))
                    .singleElement()
                    .satisfies(savedRound -> {
                        assertThat(savedRound.archivedAt()).isEqualTo(FIXED_INSTANT);
                        assertThat(savedRound.routineExecutions()).singleElement()
                                .satisfies(execution ->
                                        assertThat(execution.status()).isEqualTo(RoutineStatus.WAITING));
                    });
        } finally {
            allowArchiveToCommit.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @DisplayName("실행 완료가 먼저 잠기면 회차 보관은 완료 커밋을 기다린 뒤 완료 상태를 보존한다")
    @Test
    void serializesRoutineExecutionCompletionBeforeSeasonRoundArchive() throws Exception {
        CreatedWorkspaceResult created = workspaceUseCase.createWorkspace(
                "workspace-round-completion-archive-lock-1",
                CREATION_KEY,
                new CreateWorkspaceCommand(
                        "실행 완료 직렬화 스터디",
                        "파일럿 시즌",
                        LocalDate.of(2026, 7, 21),
                        LocalDate.of(2026, 8, 31),
                        List.of("박민서")
                )
        );
        RoleResult role = workspaceUseCase.createRole(
                created.teamId(),
                created.seasonId(),
                contentIdempotencyKey("completion-archive-lock-role"),
                created.accessKey(),
                new CreateRoleCommand(
                        "진행자", "모임을 진행합니다", null, null, null, null, List.of(), null)
        );
        workspaceUseCase.createRoutine(
                created.teamId(),
                created.seasonId(),
                contentIdempotencyKey("completion-archive-lock-routine"),
                created.accessKey(),
                new CreateRoutineCommand(
                        "질문 모으기", RoutinePhase.BEFORE, "모임 전", role.id(), "질문을 모읍니다")
        );
        SeasonRoundResult round = workspaceUseCase.createSeasonRound(
                created.teamId(),
                created.seasonId(),
                contentIdempotencyKey("completion-archive-lock-round"),
                created.accessKey(),
                new CreateSeasonRoundCommand("1회차", LocalDate.of(2026, 7, 28))
        );
        UUID executionId = round.routineExecutions().getFirst().id();
        CountDownLatch completionHasParentLock = new CountDownLatch(1);
        CountDownLatch archiveAttemptsParentLock = new CountDownLatch(1);
        CountDownLatch archiveHasParentLock = new CountDownLatch(1);
        CountDownLatch allowCompletionToCommit = new CountDownLatch(1);
        WorkspaceRepository coordinatedRepository = mock(
                WorkspaceRepository.class,
                delegatesTo(workspaceRepository)
        );
        doAnswer(invocation -> {
            Object lockedRound = workspaceRepository.findSeasonRoundBySeasonIdAndIdWithSharedLock(
                    invocation.getArgument(0),
                    invocation.getArgument(1)
            );
            completionHasParentLock.countDown();
            allowCompletionToCommit.await(10, TimeUnit.SECONDS);
            return lockedRound;
        }).when(coordinatedRepository).findSeasonRoundBySeasonIdAndIdWithSharedLock(
                any(UUID.class),
                any(UUID.class)
        );
        doAnswer(invocation -> {
            archiveAttemptsParentLock.countDown();
            Object lockedRound = workspaceRepository.findSeasonRoundBySeasonIdAndIdForUpdate(
                    invocation.getArgument(0),
                    invocation.getArgument(1)
            );
            archiveHasParentLock.countDown();
            return lockedRound;
        }).when(coordinatedRepository).findSeasonRoundBySeasonIdAndIdForUpdate(
                any(UUID.class),
                any(UUID.class)
        );
        WorkspaceService coordinatedService = new WorkspaceService(
                coordinatedRepository,
                Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC),
                CREATION_KEY,
                RECOVERY_KEY
        );
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<RoutineExecutionResult> completion = executor.submit(() ->
                    transactionTemplate.execute(status ->
                            coordinatedService.updateRoutineExecutionCompletion(
                                    created.teamId(),
                                    created.seasonId(),
                                    round.id(),
                                    executionId,
                                    created.accessKey(),
                                    true
                            )));
            assertThat(completionHasParentLock.await(10, TimeUnit.SECONDS)).isTrue();
            Future<SeasonRoundResult> archived = executor.submit(() ->
                    transactionTemplate.execute(status -> coordinatedService.updateSeasonRoundArchive(
                            created.teamId(),
                            created.seasonId(),
                            round.id(),
                            created.accessKey(),
                            true
                    )));
            assertThat(archiveAttemptsParentLock.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(archiveHasParentLock.await(1, TimeUnit.SECONDS)).isFalse();

            allowCompletionToCommit.countDown();

            assertThat(completion.get(30, TimeUnit.SECONDS).status()).isEqualTo(RoutineStatus.DONE);
            assertThat(archiveHasParentLock.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(archived.get(30, TimeUnit.SECONDS))
                    .satisfies(archivedRound -> {
                        assertThat(archivedRound.archivedAt()).isEqualTo(FIXED_INSTANT);
                        assertThat(archivedRound.routineExecutions()).singleElement()
                                .satisfies(execution ->
                                        assertThat(execution.status()).isEqualTo(RoutineStatus.DONE));
                    });
            assertThat(workspaceUseCase.getWorkspace(
                    created.teamId(),
                    created.seasonId(),
                    created.accessKey()
            ).rounds()).filteredOn(savedRound -> savedRound.id().equals(round.id()))
                    .singleElement()
                    .satisfies(savedRound -> {
                        assertThat(savedRound.archivedAt()).isEqualTo(FIXED_INSTANT);
                        assertThat(savedRound.routineExecutions()).singleElement()
                                .satisfies(execution ->
                                        assertThat(execution.status()).isEqualTo(RoutineStatus.DONE));
                    });
        } finally {
            allowCompletionToCommit.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @DisplayName("같은 버전의 역할 자료를 읽은 두 저장은 링크 수정을 모두 커밋할 수 없다")
    @Test
    void rejectsStaleRoleResourceUpdate() {
        CreatedWorkspaceResult created = workspaceUseCase.createWorkspace(
                "workspace-resource-optimistic-lock-001",
                CREATION_KEY,
                new CreateWorkspaceCommand(
                        "자료 충돌 스터디",
                        "파일럿 시즌",
                        LocalDate.of(2026, 7, 21),
                        LocalDate.of(2026, 8, 31),
                        List.of("박민서")
                )
        );
        RoleResult role = workspaceUseCase.createRole(
                created.teamId(),
                created.seasonId(),
                contentIdempotencyKey("resource-lock-role"),
                created.accessKey(),
                new CreateRoleCommand(
                        "진행자", "모임을 진행합니다", null, null, null, null, List.of(), null)
        );
        RoleResourceResult resource = workspaceUseCase.createRoleResource(
                created.teamId(),
                created.seasonId(),
                contentIdempotencyKey("resource-lock-resource"),
                created.accessKey(),
                new CreateRoleResourceCommand(
                        role.id(), "운영 가이드", "https://docs.example.com/guide", null)
        );
        EntityManager firstEntityManager = entityManagerFactory.createEntityManager();
        EntityManager secondEntityManager = entityManagerFactory.createEntityManager();

        try {
            RoleResource first = firstEntityManager.find(RoleResource.class, resource.id());
            RoleResource stale = secondEntityManager.find(RoleResource.class, resource.id());
            firstEntityManager.detach(first);
            secondEntityManager.detach(stale);

            first.update(role.id(), "운영 가이드 2판", "https://docs.example.com/guide-v2", null);
            stale.update(role.id(), "운영 가이드 3판", "https://docs.example.com/guide-v3", null);
            workspaceRepository.saveRoleResource(first);

            assertThatThrownBy(() -> workspaceRepository.saveRoleResource(stale))
                    .isInstanceOf(WorkspaceContentConflictException.class);
        } finally {
            firstEntityManager.close();
            secondEntityManager.close();
        }
    }

    @DisplayName("같은 버전의 결정과 바통을 읽은 두 저장은 변경을 모두 커밋할 수 없다")
    @Test
    void rejectsStaleDecisionAndHandoffItemUpdates() {
        CreatedWorkspaceResult created = workspaceUseCase.createWorkspace(
                "workspace-record-optimistic-lock-001",
                CREATION_KEY,
                new CreateWorkspaceCommand(
                        "기록 충돌 스터디",
                        "파일럿 시즌",
                        LocalDate.of(2026, 7, 21),
                        LocalDate.of(2026, 8, 31),
                        List.of("박민서")
                )
        );
        MemberResult member = workspaceUseCase.getWorkspace(
                created.teamId(),
                created.seasonId(),
                created.accessKey()
        ).members().getFirst();
        RoleResult role = workspaceUseCase.createRole(
                created.teamId(),
                created.seasonId(),
                contentIdempotencyKey("record-lock-role"),
                created.accessKey(),
                new CreateRoleCommand(
                        "기록자", "기록을 관리합니다", member.id(), null, null, null, List.of(), null)
        );
        DecisionResult decision = workspaceUseCase.createDecision(
                created.teamId(),
                created.seasonId(),
                contentIdempotencyKey("record-lock-decision"),
                created.accessKey(),
                new CreateDecisionCommand(
                        "원래 결정", "원래 이유", "", member.id(), List.of(role.id()))
        );
        HandoffItemResult handoffItem = workspaceUseCase.createHandoffItem(
                created.teamId(),
                created.seasonId(),
                contentIdempotencyKey("record-lock-handoff"),
                created.accessKey(),
                new CreateHandoffItemCommand(
                        role.id(), "원래 바통", HandoffCategory.RESPONSIBILITY)
        );

        EntityManager firstEntityManager = entityManagerFactory.createEntityManager();
        EntityManager secondEntityManager = entityManagerFactory.createEntityManager();
        try {
            Decision firstDecision = firstEntityManager.find(Decision.class, decision.id());
            Decision staleDecision = secondEntityManager.find(Decision.class, decision.id());
            firstDecision.getRoleIds().size();
            staleDecision.getRoleIds().size();
            firstEntityManager.detach(firstDecision);
            secondEntityManager.detach(staleDecision);
            firstDecision.update("첫 결정", "첫 이유", "", member.id(), List.of(role.id()));
            staleDecision.update("늦은 결정", "늦은 이유", "", member.id(), List.of(role.id()));
            workspaceRepository.saveDecision(firstDecision);
            assertThatThrownBy(() -> workspaceRepository.saveDecision(staleDecision))
                    .isInstanceOf(WorkspaceContentConflictException.class);
        } finally {
            firstEntityManager.close();
            secondEntityManager.close();
        }

        EntityManager thirdEntityManager = entityManagerFactory.createEntityManager();
        EntityManager fourthEntityManager = entityManagerFactory.createEntityManager();
        try {
            HandoffItem firstHandoffItem = thirdEntityManager.find(HandoffItem.class, handoffItem.id());
            HandoffItem staleHandoffItem = fourthEntityManager.find(HandoffItem.class, handoffItem.id());
            thirdEntityManager.detach(firstHandoffItem);
            fourthEntityManager.detach(staleHandoffItem);
            firstHandoffItem.update(role.id(), "첫 바통", HandoffCategory.RESOURCE);
            staleHandoffItem.update(role.id(), "늦은 바통", HandoffCategory.ADVICE);
            workspaceRepository.saveHandoffItem(firstHandoffItem);
            assertThatThrownBy(() -> workspaceRepository.saveHandoffItem(staleHandoffItem))
                    .isInstanceOf(WorkspaceContentConflictException.class);
        } finally {
            thirdEntityManager.close();
            fourthEntityManager.close();
        }
    }

    @DisplayName("다음 시즌 생성은 선택한 역할과 루틴 정의만 복사하고 원본 기록과 멱등 매핑을 보존한다")
    @Test
    void createsNextSeasonWithSelectedDefinitionsAndPreservesHistory() {
        CreatedWorkspaceResult created = workspaceUseCase.createWorkspace(
                "workspace-next-season-flow-000001",
                CREATION_KEY,
                new CreateWorkspaceCommand(
                        "시즌 전환 스터디",
                        "여름 시즌",
                        LocalDate.of(2026, 7, 1),
                        LocalDate.of(2026, 8, 31),
                        List.of("박민서")
                )
        );
        MemberResult member = workspaceUseCase.getWorkspace(
                created.teamId(),
                created.seasonId(),
                created.accessKey()
        ).members().getFirst();
        RoleResult role = workspaceUseCase.createRole(
                created.teamId(),
                created.seasonId(),
                contentIdempotencyKey("next-season-role"),
                created.accessKey(),
                new CreateRoleCommand(
                        "진행자",
                        "모임을 진행합니다",
                        member.id(),
                        null,
                        LocalDate.of(2026, 7, 1),
                        LocalDate.of(2026, 8, 31),
                        List.of("시간 확인"),
                        "발언 편중"
                )
        );
        RoutineResult routine = workspaceUseCase.createRoutine(
                created.teamId(),
                created.seasonId(),
                contentIdempotencyKey("next-season-routine"),
                created.accessKey(),
                new CreateRoutineCommand(
                        "질문 모으기",
                        RoutinePhase.BEFORE,
                        "모임 전날",
                        role.id(),
                        "질문을 한곳에 모읍니다"
                )
        );
        workspaceUseCase.createSeasonRound(
                created.teamId(),
                created.seasonId(),
                contentIdempotencyKey("next-season-round"),
                created.accessKey(),
                new CreateSeasonRoundCommand("1회차", LocalDate.of(2026, 7, 10))
        );
        workspaceUseCase.createDecision(
                created.teamId(),
                created.seasonId(),
                contentIdempotencyKey("next-season-decision"),
                created.accessKey(),
                new CreateDecisionCommand(
                        "질문은 전날 마감한다",
                        "준비 시간을 확보합니다",
                        "",
                        member.id(),
                        List.of(role.id())
                )
        );
        workspaceUseCase.createHandoffItem(
                created.teamId(),
                created.seasonId(),
                contentIdempotencyKey("next-season-handoff"),
                created.accessKey(),
                new CreateHandoffItemCommand(
                        role.id(),
                        "진행 문서 전달",
                        HandoffCategory.RESOURCE
                )
        );
        workspaceUseCase.createRoleResource(
                created.teamId(),
                created.seasonId(),
                contentIdempotencyKey("next-season-resource"),
                created.accessKey(),
                new CreateRoleResourceCommand(
                        role.id(),
                        "진행 가이드",
                        "https://docs.example.com/facilitation",
                        null
                )
        );
        WorkspaceUseCase.CreateNextSeasonCommand command =
                new WorkspaceUseCase.CreateNextSeasonCommand(
                        "가을 시즌",
                        LocalDate.of(2026, 9, 1),
                        LocalDate.of(2026, 10, 31),
                        List.of(role.id()),
                        List.of(routine.id())
                );
        String idempotencyKey = contentIdempotencyKey("next-season-create");

        workspaceUseCase.updateSeasonEnding(
                created.teamId(),
                created.seasonId(),
                created.accessKey(),
                true
        );
        WorkspaceUseCase.NextSeasonResult next = workspaceUseCase.createNextSeason(
                created.teamId(),
                created.seasonId(),
                idempotencyKey,
                created.accessKey(),
                command
        );
        WorkspaceUseCase.NextSeasonResult replayed = workspaceUseCase.createNextSeason(
                created.teamId(),
                created.seasonId(),
                idempotencyKey,
                created.accessKey(),
                command
        );

        assertThat(replayed).isEqualTo(next);
        assertThat(next.sourceSeason().endedAt()).isEqualTo(FIXED_INSTANT);
        assertThat(next.season().previousSeasonId()).isEqualTo(created.seasonId());
        assertThat(next.copiedRoles()).singleElement().satisfies(mapping -> {
            assertThat(mapping.sourceRoleId()).isEqualTo(role.id());
            assertThat(mapping.roleId()).isNotEqualTo(role.id());
        });
        assertThat(next.copiedRoutines()).singleElement().satisfies(mapping -> {
            assertThat(mapping.sourceRoutineId()).isEqualTo(routine.id());
            assertThat(mapping.routineId()).isNotEqualTo(routine.id());
        });

        WorkspaceResult source = workspaceUseCase.getWorkspace(
                created.teamId(),
                created.seasonId(),
                created.accessKey()
        );
        WorkspaceResult target = workspaceUseCase.getWorkspace(
                created.teamId(),
                next.season().id(),
                created.accessKey()
        );
        assertThat(source.rounds()).hasSize(1);
        assertThat(source.decisions()).hasSize(1);
        assertThat(source.handoffItems()).hasSize(1);
        assertThat(source.resources()).hasSize(1);
        assertThat(source.seasons()).hasSize(2);
        assertThat(target.seasons()).hasSize(2);
        assertThat(target.roles()).singleElement().satisfies(copied -> {
            assertThat(copied.name()).isEqualTo(role.name());
            assertThat(copied.currentMemberId()).isNull();
            assertThat(copied.nextMemberId()).isNull();
            assertThat(copied.assignmentStartDate()).isNull();
            assertThat(copied.assignmentEndDate()).isNull();
        });
        assertThat(target.routines()).singleElement().satisfies(copied ->
                assertThat(copied.ownerRoleId()).isEqualTo(target.roles().getFirst().id()));
        assertThat(target.rounds()).isEmpty();
        assertThat(target.decisions()).isEmpty();
        assertThat(target.handoffItems()).isEmpty();
        assertThat(target.resources()).isEmpty();

        assertThatThrownBy(() -> workspaceUseCase.createMember(
                created.teamId(),
                created.seasonId(),
                contentIdempotencyKey("next-season-closed-member"),
                created.accessKey(),
                new CreateMemberCommand("김준호")
        )).isInstanceOf(SeasonEndedException.class);
        assertThatThrownBy(() -> workspaceUseCase.updateSeasonEnding(
                created.teamId(),
                created.seasonId(),
                created.accessKey(),
                false
        )).isInstanceOf(SeasonSuccessorExistsException.class);
        assertThatThrownBy(() -> workspaceUseCase.createRoutine(
                created.teamId(),
                next.season().id(),
                contentIdempotencyKey("next-season-cross-role"),
                created.accessKey(),
                new CreateRoutineCommand(
                        "이전 역할 참조",
                        RoutinePhase.BEFORE,
                        "모임 전",
                        role.id(),
                        "이전 시즌 역할은 참조할 수 없습니다"
                )
        )).isInstanceOfSatisfying(
                WorkspaceNotFoundException.class,
                exception -> assertThat(exception.getCode()).isEqualTo("ROLE_NOT_FOUND")
        );
    }

    @DisplayName("시즌 기간은 기존 회차와 역할 배정 기간을 제외하도록 줄일 수 없다")
    @Test
    void rejectsSeasonRangeThatExcludesExistingContent() {
        CreatedWorkspaceResult created = workspaceUseCase.createWorkspace(
                "workspace-season-range-guard-000001",
                CREATION_KEY,
                new CreateWorkspaceCommand(
                        "시즌 기간 스터디",
                        "여름 시즌",
                        LocalDate.of(2026, 7, 1),
                        LocalDate.of(2026, 8, 31),
                        List.of("박민서")
                )
        );
        workspaceUseCase.createRole(
                created.teamId(),
                created.seasonId(),
                contentIdempotencyKey("season-range-role"),
                created.accessKey(),
                new CreateRoleCommand(
                        "진행자",
                        "모임을 진행합니다",
                        null,
                        null,
                        LocalDate.of(2026, 7, 5),
                        LocalDate.of(2026, 8, 25),
                        List.of(),
                        null
                )
        );
        workspaceUseCase.createSeasonRound(
                created.teamId(),
                created.seasonId(),
                contentIdempotencyKey("season-range-round"),
                created.accessKey(),
                new CreateSeasonRoundCommand("1회차", LocalDate.of(2026, 8, 20))
        );

        assertThatThrownBy(() -> workspaceUseCase.updateSeason(
                created.teamId(),
                created.seasonId(),
                created.accessKey(),
                new WorkspaceUseCase.UpdateSeasonCommand(
                        "여름 시즌",
                        LocalDate.of(2026, 7, 10),
                        LocalDate.of(2026, 8, 31)
                )
        )).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> workspaceUseCase.updateSeason(
                created.teamId(),
                created.seasonId(),
                created.accessKey(),
                new WorkspaceUseCase.UpdateSeasonCommand(
                        "여름 시즌",
                        LocalDate.of(2026, 7, 1),
                        LocalDate.of(2026, 8, 15)
                )
        )).isInstanceOf(DomainValidationException.class);
    }

    @DisplayName("접근 키 변경 재생은 시즌을 바꿔도 팀 범위를 유지하고 기존 시즌 기반 기록도 복원한다")
    @Test
    void replaysTeamScopedAndLegacySeasonScopedAccessKeyChanges() throws Exception {
        CreatedWorkspaceResult created = workspaceUseCase.createWorkspace(
                "workspace-access-key-season-transition-01",
                CREATION_KEY,
                new CreateWorkspaceCommand(
                        "접근 키 시즌 전환 스터디",
                        "여름 시즌",
                        LocalDate.of(2026, 7, 1),
                        LocalDate.of(2026, 8, 31),
                        List.of("박민서")
                )
        );
        String teamScopedIdempotencyKey = "rotate-team-scope-across-seasons-0001";
        WorkspaceUseCase.AccessKeyResult rotated = workspaceUseCase.rotateAccessKey(
                created.teamId(),
                created.seasonId(),
                teamScopedIdempotencyKey,
                created.accessKey()
        );
        WorkspaceUseCase.NextSeasonResult next = workspaceUseCase.createNextSeason(
                created.teamId(),
                created.seasonId(),
                contentIdempotencyKey("access-key-next-season"),
                rotated.accessKey(),
                new WorkspaceUseCase.CreateNextSeasonCommand(
                        "가을 시즌",
                        LocalDate.of(2026, 9, 1),
                        LocalDate.of(2026, 10, 31),
                        List.of(),
                        List.of()
                )
        );

        WorkspaceUseCase.AccessKeyResult replayedAcrossSeason = workspaceUseCase.rotateAccessKey(
                created.teamId(),
                next.season().id(),
                teamScopedIdempotencyKey,
                "재생에서는 현재 키를 다시 요구하지 않습니다"
        );

        assertThat(replayedAcrossSeason).isEqualTo(rotated);

        String legacyIdempotencyKey = "rotate-legacy-season-scope-replay-001";
        String legacyHash = legacyAccessKeyChangeHash(
                "baton:workspace-access-key-rotate-idempotency:v1",
                created.teamId(),
                next.season().id(),
                legacyIdempotencyKey
        );
        String legacyAccessKey = legacyAccessKey(
                "baton:workspace-access-key-rotate:v1",
                created.teamId(),
                next.season().id(),
                legacyIdempotencyKey
        );
        jdbcTemplate.update(
                "UPDATE teams SET access_key_hash = ?, "
                        + "last_access_key_change_idempotency_hash = ?, version = version + 1 "
                        + "WHERE id = UUID_TO_BIN(?)",
                HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256")
                                .digest(legacyAccessKey.getBytes(StandardCharsets.UTF_8))
                ),
                legacyHash,
                created.teamId().toString()
        );
        jdbcTemplate.update(
                "INSERT INTO access_key_change_history (id, team_id, idempotency_hash) "
                        + "VALUES (UUID_TO_BIN(UUID()), UUID_TO_BIN(?), ?)",
                created.teamId().toString(),
                legacyHash
        );

        WorkspaceUseCase.AccessKeyResult legacyReplay = workspaceUseCase.rotateAccessKey(
                created.teamId(),
                next.season().id(),
                legacyIdempotencyKey,
                "기존 기록 재생에서는 현재 키를 사용하지 않습니다"
        );

        assertThat(legacyReplay.accessKey()).isEqualTo(legacyAccessKey);
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

    private Object runMemberCreationTransaction(
            TransactionTemplate transactionTemplate,
            WorkspaceService service,
            CreatedWorkspaceResult workspace,
            String idempotencyKey,
            CreateMemberCommand command
    ) {
        try {
            return transactionTemplate.execute(status -> service.createMember(
                    workspace.teamId(),
                    workspace.seasonId(),
                    idempotencyKey,
                    workspace.accessKey(),
                    command
            ));
        } catch (MemberNameConflictException exception) {
            return exception;
        }
    }

    private Object runAccessKeyRotationTransaction(
            TransactionTemplate transactionTemplate,
            WorkspaceService service,
            CreatedWorkspaceResult workspace,
            String idempotencyKey
    ) {
        try {
            return transactionTemplate.execute(status -> service.rotateAccessKey(
                    workspace.teamId(),
                    workspace.seasonId(),
                    idempotencyKey,
                    workspace.accessKey()
            ));
        } catch (WorkspaceAccessKeyConflictException exception) {
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

    private String legacyAccessKeyChangeHash(
            String domain,
            UUID teamId,
            UUID seasonId,
            String idempotencyKey
    ) throws Exception {
        return HexFormat.of().formatHex(legacyAccessKeyChangeDigest(
                domain,
                teamId,
                seasonId,
                idempotencyKey
        ));
    }

    private String legacyAccessKey(
            String domain,
            UUID teamId,
            UUID seasonId,
            String idempotencyKey
    ) throws Exception {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                legacyAccessKeyChangeDigest(domain, teamId, seasonId, idempotencyKey)
        );
    }

    private String legacyInitialAccessKey(String idempotencyKey) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        updateLengthPrefixedDigest(digest, "baton:workspace-access:v1");
        updateLengthPrefixedDigest(digest, idempotencyKey);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest.digest());
    }

    private byte[] legacyAccessKeyChangeDigest(
            String domain,
            UUID teamId,
            UUID seasonId,
            String idempotencyKey
    ) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        updateLengthPrefixedDigest(digest, domain);
        updateLengthPrefixedDigest(digest, teamId.toString());
        updateLengthPrefixedDigest(digest, seasonId.toString());
        updateLengthPrefixedDigest(digest, idempotencyKey);
        return digest.digest();
    }

    private void updateLengthPrefixedDigest(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
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
