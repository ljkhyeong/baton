package com.personal.baton.application.workspace;

import com.personal.baton.BatonApplication;
import com.personal.baton.application.workspace.error.RoleNameConflictException;
import com.personal.baton.application.workspace.error.WorkspaceAccessDeniedException;
import com.personal.baton.application.workspace.error.WorkspaceNotFoundException;
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
import com.personal.baton.domain.workspace.HandoffCategory;
import com.personal.baton.domain.workspace.RoutinePhase;
import com.personal.baton.domain.workspace.RoutineStatus;
import jakarta.persistence.EntityManagerFactory;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("usecase")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        classes = {BatonApplication.class, WorkspaceUseCaseTest.FixedClockConfiguration.class},
        properties = {
                "spring.jpa.properties.hibernate.generate_statistics=true",
                "spring.session.store-type=none"
        }
)
class WorkspaceUseCaseTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-07-20T03:04:05Z");

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

    @DisplayName("워크스페이스 생성부터 모든 기록과 완료 처리까지 저장하고 접근 키와 projection 계약을 지킨다")
    @Test
    void persistsCompleteWorkspaceFlowAndEnforcesAccessKey() {
        CreatedWorkspaceResult created = workspaceUseCase.createWorkspace(new CreateWorkspaceCommand(
                "알고리즘 한 바퀴",
                "2026 여름 시즌",
                LocalDate.of(2026, 7, 2),
                LocalDate.of(2026, 9, 17),
                List.of("박민서", "김준호")
        ));

        assertThat(created.accessKey()).matches("[A-Za-z0-9_-]{43}");
        String storedHash = jdbcTemplate.queryForObject("SELECT access_key_hash FROM teams", String.class);
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
                created.accessKey(),
                new CreateDecisionCommand(
                        "중복 역할 결정",
                        "같은 역할을 두 번 연결할 수 없습니다",
                        "",
                        minseo.id(),
                        List.of(role.id(), role.id())
                )
        )).isInstanceOfSatisfying(
                com.personal.baton.domain.workspace.DomainValidationException.class,
                exception -> assertThat(exception.getMessage()).isEqualTo("관련 역할은 중복될 수 없습니다")
        );

        HandoffItemResult handoffItem = workspaceUseCase.createHandoffItem(
                created.teamId(),
                created.seasonId(),
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

        CreatedWorkspaceResult otherWorkspace = workspaceUseCase.createWorkspace(new CreateWorkspaceCommand(
                "다른 팀",
                "다른 시즌",
                LocalDate.of(2026, 7, 20),
                LocalDate.of(2026, 8, 20),
                List.of("다른 구성원")
        ));
        assertThatThrownBy(() -> workspaceUseCase.createRoutine(
                otherWorkspace.teamId(),
                otherWorkspace.seasonId(),
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

    private MemberResult memberNamed(WorkspaceResult workspace, String name) {
        return workspace.members().stream()
                .filter(member -> member.name().equals(name))
                .findFirst()
                .orElseThrow();
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
