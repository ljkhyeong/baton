package com.personal.baton.application.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.personal.baton.application.calendar.CalendarChangeRecorder;
import com.personal.baton.application.workspace.port.in.WorkspaceLifecycleCommands.UpdateSeasonCommand;
import com.personal.baton.application.workspace.port.out.WorkspaceOperationsRepository;
import com.personal.baton.application.workspace.port.out.WorkspacePeopleRepository;
import com.personal.baton.application.workspace.port.out.WorkspaceSeasonRepository;
import com.personal.baton.domain.workspace.DomainValidationException;
import com.personal.baton.domain.workspace.Season;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("usecase")
class WorkspaceSeasonSettingsCoordinatorTest {

    @Test
    @DisplayName("시즌 이름만 바꾸면 기존 회차와 역할·인수인계의 기간을 다시 조회하지 않는다")
    void renamesSeasonWithoutReloadingExistingContent() {
        WorkspaceSeasonRepository seasonRepository = mock(WorkspaceSeasonRepository.class);
        WorkspaceOperationsRepository operationsRepository = mock(WorkspaceOperationsRepository.class);
        WorkspacePeopleRepository peopleRepository = mock(WorkspacePeopleRepository.class);
        Season season = Season.create(
                UUID.randomUUID(), UUID.randomUUID(), "여름 시즌",
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 8, 31)
        );
        when(seasonRepository.saveSeason(season)).thenReturn(season);
        WorkspaceSeasonSettingsCoordinator coordinator = new WorkspaceSeasonSettingsCoordinator(
                seasonRepository,
                operationsRepository,
                peopleRepository,
                new WorkspaceResultMapper(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)),
                new WorkspaceRoundSchedulePolicy(operationsRepository),
                mock(CalendarChangeRecorder.class),
                mock(BriefContinuitySignalRecorder.class)
        );

        var result = coordinator.updateSeason(
                season,
                new UpdateSeasonCommand("여름 스터디", season.getStartDate(), season.getEndDate())
        );

        assertThat(result.name()).isEqualTo("여름 스터디");
        assertThat(result.startDate()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(result.endDate()).isEqualTo(LocalDate.of(2026, 8, 31));
        verify(operationsRepository, never()).existsSeasonRoundOutsideRange(any(), any(), any());
        verify(peopleRepository, never()).existsRoleAssignmentOutsideRange(any(), any(), any(), any());
        verify(peopleRepository, never()).existsOpenRoleHandoffOutsideRange(any(), any(), any(), any());
    }

    @Test
    @DisplayName("후속 시즌의 시작일은 이전 시즌 종료일보다 늦어야 한다")
    void keepsSuccessorStartAfterPreviousSeasonEnd() {
        WorkspaceSeasonRepository seasonRepository = mock(WorkspaceSeasonRepository.class);
        WorkspaceOperationsRepository operationsRepository = mock(WorkspaceOperationsRepository.class);
        UUID teamId = UUID.randomUUID();
        Season previous = Season.create(
                UUID.randomUUID(), teamId, "여름 시즌",
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 8, 31)
        );
        Season successor = Season.createSuccessor(
                UUID.randomUUID(), teamId, previous.getId(), "가을 시즌",
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 31), "Asia/Seoul"
        );
        when(seasonRepository.findSeasonById(previous.getId())).thenReturn(Optional.of(previous));
        when(seasonRepository.saveSeason(successor)).thenReturn(successor);
        WorkspaceSeasonSettingsCoordinator coordinator = new WorkspaceSeasonSettingsCoordinator(
                seasonRepository,
                operationsRepository,
                mock(WorkspacePeopleRepository.class),
                new WorkspaceResultMapper(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)),
                new WorkspaceRoundSchedulePolicy(operationsRepository),
                mock(CalendarChangeRecorder.class),
                mock(BriefContinuitySignalRecorder.class)
        );

        assertThatThrownBy(() -> coordinator.updateSeason(
                successor,
                new UpdateSeasonCommand("가을 시즌", LocalDate.of(2026, 8, 31), LocalDate.of(2026, 10, 31))
        )).isInstanceOf(DomainValidationException.class);
        verify(seasonRepository, never()).saveSeason(any());

        assertThat(coordinator.updateSeason(
                successor,
                new UpdateSeasonCommand("가을 시즌", LocalDate.of(2026, 9, 2), LocalDate.of(2026, 10, 31))
        ).startDate()).isEqualTo(LocalDate.of(2026, 9, 2));
    }
}
