package com.personal.baton.application.workspace;

import com.personal.baton.application.workspace.error.WorkspaceNotFoundException;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.CreateNextSeasonCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.NextSeasonResult;
import com.personal.baton.application.workspace.port.out.WorkspaceRepository;
import com.personal.baton.application.watch.WatchMonitorChangeRecorder;
import com.personal.baton.domain.workspace.ContentCreationIdempotency;
import com.personal.baton.domain.workspace.Role;
import com.personal.baton.domain.workspace.Routine;
import com.personal.baton.domain.workspace.RoutinePhase;
import com.personal.baton.domain.workspace.Season;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
final class WorkspaceSeasonLifecycleCoordinatorTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-31T12:00:00Z"),
            ZoneOffset.UTC
    );

    @Mock
    private WorkspaceRepository repository;

    @Mock
    private WatchMonitorChangeRecorder watchMonitorChangeRecorder;

    @DisplayName("다음 시즌 정의는 역할과 루틴을 순서대로 한 번씩 일괄 저장한다")
    @Test
    void savesCopiedDefinitionsInOrderedBatches() {
        UUID teamId = UUID.randomUUID();
        UUID sourceSeasonId = UUID.randomUUID();
        Season sourceSeason = Season.create(
                sourceSeasonId,
                teamId,
                "여름 시즌",
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 8, 31)
        );
        Role firstRole = role(teamId, sourceSeasonId, "진행자");
        Role secondRole = role(teamId, sourceSeasonId, "기록자");
        Routine firstRoutine = routine(sourceSeasonId, firstRole.getId(), "질문 모으기");
        Routine secondRoutine = routine(sourceSeasonId, secondRole.getId(), "결정 정리하기");
        List<Role> sourceRoles = List.of(firstRole, secondRole);
        List<Routine> sourceRoutines = List.of(firstRoutine, secondRoutine);
        AtomicReference<List<Role>> savedRoles = new AtomicReference<>(List.of());
        AtomicReference<List<Routine>> savedRoutines = new AtomicReference<>(List.of());

        when(repository.findContentCreationIdempotency(eq(teamId), anyString()))
                .thenReturn(Optional.empty());
        when(repository.findActiveSeasonByTeamId(teamId)).thenReturn(Optional.of(sourceSeason));
        when(repository.findRolesByTeamIdAndSeasonId(eq(teamId), any(UUID.class)))
                .thenAnswer(invocation -> sourceSeasonId.equals(invocation.getArgument(1))
                        ? sourceRoles
                        : savedRoles.get());
        when(repository.findRoutinesBySeasonId(any(UUID.class)))
                .thenAnswer(invocation -> sourceSeasonId.equals(invocation.getArgument(0))
                        ? sourceRoutines
                        : savedRoutines.get());
        when(repository.saveSeason(any(Season.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.saveContentCreationIdempotency(any(ContentCreationIdempotency.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.saveRoles(anyList())).thenAnswer(invocation -> {
            List<Role> roles = List.copyOf(invocation.getArgument(0));
            savedRoles.set(roles);
            return roles;
        });
        when(repository.saveRoutines(anyList())).thenAnswer(invocation -> {
            List<Routine> routines = List.copyOf(invocation.getArgument(0));
            savedRoutines.set(routines);
            return routines;
        });

        WorkspaceSeasonLifecycleCoordinator coordinator =
                new WorkspaceSeasonLifecycleCoordinator(
                        repository,
                        CLOCK,
                        new WorkspaceContentIdempotency(repository),
                        new WorkspaceResultMapper(CLOCK),
                        watchMonitorChangeRecorder
                );

        NextSeasonResult result = coordinator.createNext(
                teamId,
                sourceSeason,
                "next-season-batch-idempotency-key-0001",
                new CreateNextSeasonCommand(
                        "가을 시즌",
                        LocalDate.of(2026, 9, 1),
                        LocalDate.of(2026, 10, 31),
                        List.of(firstRole.getId(), secondRole.getId()),
                        List.of(firstRoutine.getId(), secondRoutine.getId())
                )
        );

        InOrder saveOrder = inOrder(repository);
        saveOrder.verify(repository).saveSeason(sourceSeason);
        saveOrder.verify(repository).saveContentCreationIdempotency(any());
        saveOrder.verify(repository).saveSeason(any(Season.class));
        saveOrder.verify(repository).saveRoles(anyList());
        saveOrder.verify(repository).saveRoutines(anyList());
        verify(repository, times(1)).saveRoles(anyList());
        verify(repository, times(1)).saveRoutines(anyList());
        verify(repository, never()).saveRole(any());
        verify(repository, never()).saveRoutine(any());

        assertThat(savedRoles.get()).hasSize(2);
        assertThat(savedRoutines.get()).hasSize(2);
        Map<UUID, UUID> copiedRoleIds = Map.of(
                firstRole.getId(), copiedRoleId(savedRoles.get(), firstRole.getId()),
                secondRole.getId(), copiedRoleId(savedRoles.get(), secondRole.getId())
        );
        assertThat(savedRoutines.get()).allSatisfy(copiedRoutine -> {
            UUID sourceOwnerRoleId = copiedRoutine.getPreviousRoutineId().equals(firstRoutine.getId())
                    ? firstRoutine.getOwnerRoleId()
                    : secondRoutine.getOwnerRoleId();
            assertThat(copiedRoutine.getOwnerRoleId())
                    .isEqualTo(copiedRoleIds.get(sourceOwnerRoleId));
        });
        assertThat(result.copiedRoles()).hasSize(2);
        assertThat(result.copiedRoutines()).hasSize(2);
    }

    @DisplayName("보관된 루틴은 다음 시즌 복사 대상으로 선택할 수 없다")
    @Test
    void excludesArchivedRoutineFromNextSeasonSelection() {
        UUID teamId = UUID.randomUUID();
        UUID sourceSeasonId = UUID.randomUUID();
        Season sourceSeason = Season.create(
                sourceSeasonId,
                teamId,
                "여름 시즌",
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 8, 31)
        );
        Role sourceRole = role(teamId, sourceSeasonId, "진행자");
        Routine archivedRoutine = routine(sourceSeasonId, sourceRole.getId(), "질문 모으기");
        archivedRoutine.updateArchive(true, CLOCK.instant());
        when(repository.findContentCreationIdempotency(eq(teamId), anyString()))
                .thenReturn(Optional.empty());
        when(repository.findActiveSeasonByTeamId(teamId)).thenReturn(Optional.of(sourceSeason));
        when(repository.findRolesByTeamIdAndSeasonId(teamId, sourceSeasonId))
                .thenReturn(List.of(sourceRole));
        when(repository.findRoutinesBySeasonId(sourceSeasonId))
                .thenReturn(List.of(archivedRoutine));
        WorkspaceSeasonLifecycleCoordinator coordinator =
                new WorkspaceSeasonLifecycleCoordinator(
                        repository,
                        CLOCK,
                        new WorkspaceContentIdempotency(repository),
                        new WorkspaceResultMapper(CLOCK),
                        watchMonitorChangeRecorder
                );

        assertThatThrownBy(() -> coordinator.createNext(
                teamId,
                sourceSeason,
                "next-season-archived-routine-key-001",
                new CreateNextSeasonCommand(
                        "가을 시즌",
                        LocalDate.of(2026, 9, 1),
                        LocalDate.of(2026, 10, 31),
                        List.of(sourceRole.getId()),
                        List.of(archivedRoutine.getId())
                )
        ))
                .isInstanceOf(WorkspaceNotFoundException.class)
                .hasMessageContaining("복사할 루틴");
        verify(repository, never()).saveSeason(any());
        verify(repository, never()).saveRoutines(anyList());
    }

    private Role role(UUID teamId, UUID seasonId, String name) {
        return Role.create(
                UUID.randomUUID(),
                teamId,
                seasonId,
                name,
                name + " 역할을 수행합니다",
                null,
                null,
                null,
                null,
                List.of(name + " 책임"),
                null
        );
    }

    private Routine routine(UUID seasonId, UUID ownerRoleId, String title) {
        return Routine.create(
                UUID.randomUUID(),
                seasonId,
                title,
                RoutinePhase.BEFORE,
                "모임 전날",
                ownerRoleId,
                title + " 작업을 수행합니다",
                null,
                null
        );
    }

    private UUID copiedRoleId(List<Role> copiedRoles, UUID sourceRoleId) {
        return copiedRoles.stream()
                .filter(role -> sourceRoleId.equals(role.getPreviousRoleId()))
                .map(Role::getId)
                .findFirst()
                .orElseThrow();
    }
}
