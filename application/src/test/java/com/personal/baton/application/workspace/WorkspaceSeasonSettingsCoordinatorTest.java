package com.personal.baton.application.workspace;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.personal.baton.domain.workspace.Season;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
                mock(CalendarChangeRecorder.class)
        );

        var result = coordinator.updateSeason(
                season.getTeamId(), season,
                new UpdateSeasonCommand("여름 스터디", season.getStartDate(), season.getEndDate())
        );

        assertThat(result.name()).isEqualTo("여름 스터디");
        assertThat(result.startDate()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(result.endDate()).isEqualTo(LocalDate.of(2026, 8, 31));
        verify(operationsRepository, never()).existsSeasonRoundOutsideRange(any(), any(), any());
        verify(peopleRepository, never()).existsRoleAssignmentOutsideRange(any(), any(), any(), any());
        verify(peopleRepository, never()).existsOpenRoleHandoffOutsideRange(any(), any(), any(), any());
    }
}
