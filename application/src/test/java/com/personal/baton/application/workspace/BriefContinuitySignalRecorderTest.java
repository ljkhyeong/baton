package com.personal.baton.application.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.personal.baton.application.brief.BriefContinuityEvent;
import com.personal.baton.application.brief.BriefContinuitySignalState;
import com.personal.baton.application.brief.port.out.BriefContinuitySignalStorePort;
import com.personal.baton.application.workspace.port.in.ContinuitySignalSeverity;
import com.personal.baton.application.workspace.port.in.ContinuitySignalType;
import com.personal.baton.application.workspace.port.out.WorkspaceOperationsRepository;
import com.personal.baton.application.workspace.port.out.WorkspacePeopleRepository;
import com.personal.baton.application.workspace.port.out.WorkspaceRecordsRepository;
import com.personal.baton.application.workspace.port.out.WorkspaceSeasonRepository;
import com.personal.baton.domain.workspace.Season;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@Tag("usecase")
class BriefContinuitySignalRecorderTest {

    @Test
    @DisplayName("종료 시즌은 신호 계산용 원본을 조회하지 않고 기존 활성 신호를 해소한다")
    void resolvesEndedSeasonWithoutLoadingSignalSources() {
        WorkspaceSeasonRepository repository = mock(WorkspaceSeasonRepository.class);
        BriefContinuitySignalStorePort storePort = mock(BriefContinuitySignalStorePort.class);
        Instant now = Instant.parse("2026-08-01T00:00:00Z");
        UUID teamId = UUID.randomUUID();
        UUID seasonId = UUID.randomUUID();
        UUID signalId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();
        Season season = Season.create(
                seasonId, teamId, "여름 시즌",
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 9, 30)
        );
        season.updateEnding(true, now);
        when(repository.findSeasonById(seasonId)).thenReturn(Optional.of(season));
        when(storePort.findBySeason(teamId, seasonId)).thenReturn(List.of(
                new BriefContinuitySignalState(
                        signalId, ContinuitySignalType.ROLE_UNASSIGNED, roleId,
                        ContinuitySignalSeverity.CRITICAL, BriefContinuityEvent.State.ACTIVE, 3
                )
        ));
        BriefContinuitySignalRecorder recorder = new BriefContinuitySignalRecorder(
                repository,
                storePort,
                Clock.fixed(now, ZoneOffset.UTC),
                new WorkspaceContinuitySnapshotReader(
                        mock(WorkspacePeopleRepository.class),
                        mock(WorkspaceOperationsRepository.class),
                        mock(WorkspaceRecordsRepository.class)
                ),
                new ContinuitySignalAnalyzer()
        );

        assertThat(recorder.reconcileSeason(teamId, seasonId)).isEqualTo(1);

        ArgumentCaptor<BriefContinuityEvent> event = ArgumentCaptor.forClass(BriefContinuityEvent.class);
        verify(storePort).append(eq(roleId), event.capture());
        assertThat(event.getValue().state()).isEqualTo(BriefContinuityEvent.State.RESOLVED);
        assertThat(event.getValue().aggregateRevision()).isEqualTo(4);
        assertThat(event.getValue().sourceReference()).isEqualTo("baton-continuity:" + signalId);
        verify(repository).findSeasonById(seasonId);
        verifyNoMoreInteractions(repository);
    }
}
