package com.personal.baton.adapter.out.persistence.workspace;

import com.personal.baton.application.workspace.error.IdempotencyKeyConflictException;
import com.personal.baton.application.workspace.error.MemberNameConflictException;
import com.personal.baton.application.workspace.error.RoleNameConflictException;
import com.personal.baton.application.workspace.error.SeasonNameConflictException;
import com.personal.baton.application.workspace.error.SeasonRoundNameConflictException;
import com.personal.baton.application.workspace.error.SeasonSuccessorExistsException;
import com.personal.baton.application.workspace.error.WorkspaceAccessKeyConflictException;
import com.personal.baton.application.workspace.error.WorkspaceContentConflictException;
import com.personal.baton.domain.workspace.ContentCreationIdempotency;
import com.personal.baton.domain.workspace.Member;
import com.personal.baton.domain.workspace.Role;
import com.personal.baton.domain.workspace.RoleHandoffStatus;
import com.personal.baton.domain.workspace.RoutineExecution;
import com.personal.baton.domain.workspace.Season;
import com.personal.baton.domain.workspace.SeasonRound;
import com.personal.baton.domain.workspace.Team;
import java.util.List;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
final class WorkspacePersistenceAdapterTest {

    @Mock
    private TeamJpaRepository teamRepository;

    @Mock
    private AccessKeyChangeHistoryJpaRepository accessKeyChangeHistoryRepository;

    @Mock
    private ContentCreationIdempotencyJpaRepository contentCreationIdempotencyRepository;

    @Mock
    private SeasonJpaRepository seasonRepository;

    @Mock
    private MemberJpaRepository memberRepository;

    @Mock
    private RoleJpaRepository roleRepository;

    @Mock
    private RoleHandoffJpaRepository roleHandoffRepository;

    @Mock
    private RoutineJpaRepository routineRepository;

    @Mock
    private SeasonRoundJpaRepository seasonRoundRepository;

    @Mock
    private RoutineExecutionJpaRepository routineExecutionRepository;

    @Mock
    private DecisionJpaRepository decisionRepository;

    @Mock
    private HandoffItemJpaRepository handoffItemRepository;

    @Mock
    private RoleResourceJpaRepository roleResourceRepository;

    @InjectMocks
    private WorkspacePersistenceAdapter adapter;

    @DisplayName("팀의 낙관적 잠금 충돌 원인을 접근 키 충돌 예외에 보존한다")
    @Test
    void preservesOptimisticLockCauseForAccessKeyConflict() {
        Team team = mock(Team.class);
        OptimisticLockingFailureException cause =
                new OptimisticLockingFailureException("팀 버전 충돌");
        when(teamRepository.saveAndFlush(team)).thenThrow(cause);

        assertThatThrownBy(() -> adapter.saveTeam(team))
                .isInstanceOfSatisfying(
                        WorkspaceAccessKeyConflictException.class,
                        exception -> {
                            assertThat(exception)
                                    .hasMessage("접근 키가 동시에 변경되었습니다. 최신 키로 다시 시도해 주세요");
                            assertThat(exception.getCause()).isSameAs(cause);
                        }
                );
    }

    @DisplayName("팀 저장의 비관적 잠금 시간 초과 원인을 접근 키 충돌 예외에 보존한다")
    @Test
    void preservesPessimisticLockCauseWhenSavingTeam() {
        Team team = mock(Team.class);
        when(team.getVersion()).thenReturn(1L);
        PessimisticLockingFailureException cause =
                new PessimisticLockingFailureException("팀 저장 잠금 시간 초과");
        when(teamRepository.saveAndFlush(team)).thenThrow(cause);

        assertThatThrownBy(() -> adapter.saveTeam(team))
                .isInstanceOfSatisfying(
                        WorkspaceAccessKeyConflictException.class,
                        exception -> {
                            assertThat(exception)
                                    .hasMessage("접근 키가 동시에 변경되었습니다. 최신 키로 다시 시도해 주세요");
                            assertThat(exception.getCause()).isSameAs(cause);
                        }
                );
    }

    @DisplayName("새 팀 저장의 잠금 시간 초과 원인을 멱등 키 충돌 예외에 보존한다")
    @Test
    void preservesPessimisticLockCauseForWorkspaceCreationIdempotencyConflict() {
        Team team = mock(Team.class);
        when(team.getVersion()).thenReturn(null);
        PessimisticLockingFailureException cause =
                new PessimisticLockingFailureException("새 팀 저장 잠금 시간 초과");
        when(teamRepository.saveAndFlush(team)).thenThrow(cause);

        assertThatThrownBy(() -> adapter.saveTeam(team))
                .isInstanceOfSatisfying(
                        IdempotencyKeyConflictException.class,
                        exception -> {
                            assertThat(exception).hasMessage(
                                    "동일한 멱등 키의 생성 요청이 처리 중입니다. 잠시 후 다시 시도해 주세요"
                            );
                            assertThat(exception.getCause()).isSameAs(cause);
                        }
                );
    }

    @DisplayName("팀의 공유 잠금 실패 원인을 접근 키 충돌 예외에 보존한다")
    @Test
    void preservesPessimisticLockCauseForAccessKeyConflict() {
        UUID teamId = UUID.randomUUID();
        PessimisticLockingFailureException cause =
                new PessimisticLockingFailureException("팀 공유 잠금 실패");
        when(teamRepository.findByIdWithSharedLock(teamId)).thenThrow(cause);

        assertThatThrownBy(() -> adapter.findTeamByIdWithSharedLock(teamId))
                .isInstanceOfSatisfying(
                        WorkspaceAccessKeyConflictException.class,
                        exception -> assertThat(exception.getCause()).isSameAs(cause)
                );
    }

    @DisplayName("ROUND 자료 공유 잠금 실패 원인을 콘텐츠 충돌 예외에 보존한다")
    @Test
    void preservesPessimisticLockCauseForRoundResourceConflict() {
        UUID resourceId = UUID.randomUUID();
        PessimisticLockingFailureException cause =
                new PessimisticLockingFailureException("ROUND 자료 공유 잠금 실패");
        when(roleResourceRepository.findByIdWithSharedLock(resourceId)).thenThrow(cause);

        assertThatThrownBy(() ->
                adapter.findRoleResourceByIdWithSharedLock(resourceId))
                .isInstanceOfSatisfying(
                        WorkspaceContentConflictException.class,
                        exception -> assertThat(exception.getCause()).isSameAs(cause)
                );
    }

    @DisplayName("콘텐츠 멱등 제약 충돌 원인을 멱등 키 충돌 예외에 보존한다")
    @Test
    void preservesConstraintCauseForIdempotencyConflict() {
        ContentCreationIdempotency idempotency = mock(ContentCreationIdempotency.class);
        DataIntegrityViolationException cause =
                uniqueConstraintViolation("uk_content_creation_idempotency_team_hash");
        when(contentCreationIdempotencyRepository.saveAndFlush(idempotency)).thenThrow(cause);

        assertThatThrownBy(() -> adapter.saveContentCreationIdempotency(idempotency))
                .isInstanceOfSatisfying(
                        IdempotencyKeyConflictException.class,
                        exception -> {
                            assertThat(exception).hasMessage(
                                    "동일한 멱등 키의 생성 요청이 처리 중입니다. 잠시 후 다시 시도해 주세요"
                            );
                            assertThat(exception.getCause()).isSameAs(cause);
                        }
                );
    }

    @DisplayName("콘텐츠 멱등 예약의 잠금 시간 초과 원인을 멱등 키 충돌 예외에 보존한다")
    @Test
    void preservesPessimisticLockCauseForContentIdempotencyConflict() {
        ContentCreationIdempotency idempotency = mock(ContentCreationIdempotency.class);
        PessimisticLockingFailureException cause =
                new PessimisticLockingFailureException("콘텐츠 멱등 예약 잠금 시간 초과");
        when(contentCreationIdempotencyRepository.saveAndFlush(idempotency)).thenThrow(cause);

        assertThatThrownBy(() -> adapter.saveContentCreationIdempotency(idempotency))
                .isInstanceOfSatisfying(
                        IdempotencyKeyConflictException.class,
                        exception -> {
                            assertThat(exception).hasMessage(
                                    "동일한 멱등 키의 생성 요청이 처리 중입니다. 잠시 후 다시 시도해 주세요"
                            );
                            assertThat(exception.getCause()).isSameAs(cause);
                        }
                );
    }

    @DisplayName("구성원 이름 제약 충돌 원인을 구성원 이름 충돌 예외에 보존한다")
    @Test
    void preservesConstraintCauseForMemberNameConflict() {
        Member member = mock(Member.class);
        DataIntegrityViolationException cause = uniqueConstraintViolation("uk_members_team_name");
        when(memberRepository.saveAndFlush(member)).thenThrow(cause);

        assertThatThrownBy(() -> adapter.saveMember(member))
                .isInstanceOfSatisfying(
                        MemberNameConflictException.class,
                        exception -> {
                            assertThat(exception)
                                    .hasMessage("같은 팀에 동일한 이름의 구성원이 이미 있습니다");
                            assertThat(exception.getCause()).isSameAs(cause);
                        }
                );
    }

    @DisplayName("구성원 저장의 낙관적 잠금 충돌 원인을 콘텐츠 충돌 예외에 보존한다")
    @Test
    void preservesOptimisticLockCauseForMemberContentConflict() {
        Member member = mock(Member.class);
        OptimisticLockingFailureException cause =
                new OptimisticLockingFailureException("구성원 버전 충돌");
        when(memberRepository.saveAndFlush(member)).thenThrow(cause);

        assertThatThrownBy(() -> adapter.saveMember(member))
                .isInstanceOfSatisfying(
                        WorkspaceContentConflictException.class,
                        exception -> assertThat(exception.getCause()).isSameAs(cause)
                );
    }

    @DisplayName("구성원 저장의 비관적 잠금 실패 원인을 콘텐츠 충돌 예외에 보존한다")
    @Test
    void preservesPessimisticLockCauseWhenSavingMember() {
        Member member = mock(Member.class);
        PessimisticLockingFailureException cause =
                new PessimisticLockingFailureException("구성원 저장 잠금 실패");
        when(memberRepository.saveAndFlush(member)).thenThrow(cause);

        assertThatThrownBy(() -> adapter.saveMember(member))
                .isInstanceOfSatisfying(
                        WorkspaceContentConflictException.class,
                        exception -> assertThat(exception.getCause()).isSameAs(cause)
                );
    }

    @DisplayName("구성원 후보 공유 잠금 실패 원인을 콘텐츠 충돌 예외에 보존한다")
    @Test
    void preservesPessimisticLockCauseForMemberAssignmentConflict() {
        UUID teamId = UUID.randomUUID();
        List<UUID> memberIds = List.of(UUID.randomUUID(), UUID.randomUUID());
        PessimisticLockingFailureException cause =
                new PessimisticLockingFailureException("구성원 후보 공유 잠금 실패");
        when(memberRepository.findAllByTeamIdAndIdInWithSharedLock(teamId, memberIds))
                .thenThrow(cause);

        assertThatThrownBy(() ->
                adapter.findMembersByTeamIdAndIdsWithSharedLock(teamId, memberIds))
                .isInstanceOfSatisfying(
                        WorkspaceContentConflictException.class,
                        exception -> assertThat(exception.getCause()).isSameAs(cause)
                );
    }

    @DisplayName("역할 이름 제약 충돌 원인을 역할 이름 충돌 예외에 보존한다")
    @Test
    void preservesConstraintCauseForRoleNameConflict() {
        Role role = mock(Role.class);
        DataIntegrityViolationException cause = uniqueConstraintViolation("uk_roles_season_name");
        when(roleRepository.saveAndFlush(role)).thenThrow(cause);

        assertThatThrownBy(() -> adapter.saveRole(role))
                .isInstanceOfSatisfying(
                        RoleNameConflictException.class,
                        exception -> {
                            assertThat(exception)
                                    .hasMessage("같은 시즌에 동일한 이름의 역할이 이미 있습니다");
                            assertThat(exception.getCause()).isSameAs(cause);
                        }
                );
    }

    @DisplayName("시즌 이름 제약 충돌 원인을 시즌 이름 충돌 예외에 보존한다")
    @Test
    void preservesConstraintCauseForSeasonNameConflict() {
        Season season = mock(Season.class);
        DataIntegrityViolationException cause = uniqueConstraintViolation("uk_seasons_team_name");
        when(seasonRepository.saveAndFlush(season)).thenThrow(cause);

        assertThatThrownBy(() -> adapter.saveSeason(season))
                .isInstanceOfSatisfying(
                        SeasonNameConflictException.class,
                        exception -> assertThat(exception.getCause()).isSameAs(cause)
                );
    }

    @DisplayName("후속 시즌 제약 충돌 원인을 후속 시즌 존재 예외에 보존한다")
    @Test
    void preservesConstraintCauseForSeasonSuccessorConflict() {
        Season season = mock(Season.class);
        DataIntegrityViolationException cause =
                uniqueConstraintViolation("uk_seasons_previous_season");
        when(seasonRepository.saveAndFlush(season)).thenThrow(cause);

        assertThatThrownBy(() -> adapter.saveSeason(season))
                .isInstanceOfSatisfying(
                        SeasonSuccessorExistsException.class,
                        exception -> assertThat(exception.getCause()).isSameAs(cause)
                );
    }

    @DisplayName("활성 시즌 제약 충돌 원인을 콘텐츠 충돌 예외에 보존한다")
    @Test
    void preservesConstraintCauseForActiveSeasonConflict() {
        Season season = mock(Season.class);
        DataIntegrityViolationException cause = uniqueConstraintViolation("uk_seasons_active_team");
        when(seasonRepository.saveAndFlush(season)).thenThrow(cause);

        assertThatThrownBy(() -> adapter.saveSeason(season))
                .isInstanceOfSatisfying(
                        WorkspaceContentConflictException.class,
                        exception -> assertThat(exception.getCause()).isSameAs(cause)
                );
    }

    @DisplayName("회차 이름 제약 충돌 원인을 회차 이름 충돌 예외에 보존한다")
    @Test
    void preservesConstraintCauseForSeasonRoundNameConflict() {
        SeasonRound seasonRound = mock(SeasonRound.class);
        DataIntegrityViolationException cause =
                uniqueConstraintViolation("uk_season_rounds_season_name");
        when(seasonRoundRepository.saveAndFlush(seasonRound)).thenThrow(cause);

        assertThatThrownBy(() -> adapter.saveSeasonRound(seasonRound))
                .isInstanceOfSatisfying(
                        SeasonRoundNameConflictException.class,
                        exception -> {
                            assertThat(exception)
                                    .hasMessage("같은 시즌에 동일한 회차 이름을 사용할 수 없습니다");
                            assertThat(exception.getCause()).isSameAs(cause);
                        }
                );
    }

    @DisplayName("콘텐츠 낙관적 잠금 충돌 원인을 콘텐츠 충돌 예외에 보존한다")
    @Test
    void preservesOptimisticLockCauseForContentConflict() {
        Role role = mock(Role.class);
        OptimisticLockingFailureException cause =
                new OptimisticLockingFailureException("콘텐츠 버전 충돌");
        when(roleRepository.saveAndFlush(role)).thenThrow(cause);

        assertThatThrownBy(() -> adapter.saveRole(role))
                .isInstanceOfSatisfying(
                        WorkspaceContentConflictException.class,
                        exception -> {
                            assertThat(exception).hasMessage(
                                    "다른 사용자가 먼저 내용을 변경했습니다. 최신 내용을 확인한 뒤 다시 시도해 주세요"
                            );
                            assertThat(exception.getCause()).isSameAs(cause);
                        }
                );
    }

    @DisplayName("콘텐츠 저장의 비관적 잠금 시간 초과 원인을 콘텐츠 충돌 예외에 보존한다")
    @Test
    void preservesPessimisticLockCauseWhenSavingContent() {
        Role role = mock(Role.class);
        PessimisticLockingFailureException cause =
                new PessimisticLockingFailureException("콘텐츠 저장 잠금 시간 초과");
        when(roleRepository.saveAndFlush(role)).thenThrow(cause);

        assertThatThrownBy(() -> adapter.saveRole(role))
                .isInstanceOfSatisfying(
                        WorkspaceContentConflictException.class,
                        exception -> {
                            assertThat(exception).hasMessage(
                                    "다른 사용자가 먼저 내용을 변경했습니다. 최신 내용을 확인한 뒤 다시 시도해 주세요"
                            );
                            assertThat(exception.getCause()).isSameAs(cause);
                        }
                );
    }

    @DisplayName("열린 역할 바통 공유 잠금 실패 원인을 콘텐츠 충돌 예외에 보존한다")
    @Test
    void preservesPessimisticLockCauseWhenCheckingOpenRoleHandoff() {
        UUID roleId = UUID.randomUUID();
        PessimisticLockingFailureException cause =
                new PessimisticLockingFailureException("열린 역할 바통 공유 잠금 실패");
        when(roleHandoffRepository.findOpenByRoleIdWithSharedLock(
                roleId,
                List.of(
                        RoleHandoffStatus.PREPARING,
                        RoleHandoffStatus.TRANSFERRED
                )
        )).thenThrow(cause);

        assertThatThrownBy(() ->
                adapter.findOpenRoleHandoffByRoleIdWithSharedLock(roleId))
                .isInstanceOfSatisfying(
                        WorkspaceContentConflictException.class,
                        exception -> assertThat(exception.getCause()).isSameAs(cause)
                );
    }

    @DisplayName("루틴 실행 일괄 저장의 낙관적·비관적 잠금 실패 원인을 콘텐츠 충돌 예외에 보존한다")
    @Test
    void preservesLockCausesWhenSavingRoutineExecutions() {
        List<RoutineExecution> executions = List.of(mock(RoutineExecution.class));
        OptimisticLockingFailureException optimisticCause =
                new OptimisticLockingFailureException("루틴 실행 버전 충돌");
        PessimisticLockingFailureException pessimisticCause =
                new PessimisticLockingFailureException("루틴 실행 일괄 저장 잠금 시간 초과");
        when(routineExecutionRepository.saveAllAndFlush(executions))
                .thenThrow(optimisticCause)
                .thenThrow(pessimisticCause);

        assertThatThrownBy(() -> adapter.saveRoutineExecutions(executions))
                .isInstanceOfSatisfying(
                        WorkspaceContentConflictException.class,
                        exception -> assertThat(exception.getCause()).isSameAs(optimisticCause)
                );
        assertThatThrownBy(() -> adapter.saveRoutineExecutions(executions))
                .isInstanceOfSatisfying(
                        WorkspaceContentConflictException.class,
                        exception -> assertThat(exception.getCause()).isSameAs(pessimisticCause)
                );
    }

    @DisplayName("회차의 배타 잠금 실패 원인을 콘텐츠 충돌 예외에 보존한다")
    @Test
    void preservesPessimisticLockCauseForContentConflict() {
        UUID seasonId = UUID.randomUUID();
        UUID seasonRoundId = UUID.randomUUID();
        PessimisticLockingFailureException cause =
                new PessimisticLockingFailureException("회차 배타 잠금 실패");
        when(seasonRoundRepository.findBySeasonIdAndIdForUpdate(seasonId, seasonRoundId))
                .thenThrow(cause);

        assertThatThrownBy(() ->
                adapter.findSeasonRoundBySeasonIdAndIdForUpdate(seasonId, seasonRoundId))
                .isInstanceOfSatisfying(
                        WorkspaceContentConflictException.class,
                        exception -> assertThat(exception.getCause()).isSameAs(cause)
                );
    }

    @DisplayName("식별하지 않은 데이터 제약 위반은 원래 예외를 그대로 전달한다")
    @Test
    void preservesUnknownDataIntegrityViolation() {
        Member member = mock(Member.class);
        DataIntegrityViolationException cause =
                uniqueConstraintViolation("uk_unknown_workspace_constraint");
        when(memberRepository.saveAndFlush(member)).thenThrow(cause);

        assertThatThrownBy(() -> adapter.saveMember(member)).isSameAs(cause);
    }

    private DataIntegrityViolationException uniqueConstraintViolation(String constraintName) {
        ConstraintViolationException constraintViolation = mock(ConstraintViolationException.class);
        when(constraintViolation.getConstraintName()).thenReturn(constraintName);
        return new DataIntegrityViolationException("고유 제약 충돌", constraintViolation);
    }
}
