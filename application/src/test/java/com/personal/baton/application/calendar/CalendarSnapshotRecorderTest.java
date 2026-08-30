package com.personal.baton.application.calendar;

import com.personal.baton.application.calendar.port.out.CalendarOutboxPort;
import com.personal.baton.domain.workspace.Routine;
import com.personal.baton.domain.workspace.RoutineExecution;
import com.personal.baton.domain.workspace.RoutinePhase;
import com.personal.baton.domain.workspace.Season;
import com.personal.baton.domain.workspace.SeasonRound;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@Tag("policy")
class CalendarSnapshotRecorderTest {

    private static final Instant NOW = Instant.parse("2026-08-25T03:00:00Z");

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    @DisplayName("시즌 이름은 캡처와 이름 연동 설정을 모두 켠 경우에만 기록한다")
    void capturesSeasonOnlyWhenBothSettingsAreEnabled(boolean capture, boolean metadata) {
        CalendarOutboxPort outbox = mock(CalendarOutboxPort.class);
        var recorder = new CalendarSnapshotRecorder(
                outbox, new CalendarCaptureState(capture, metadata), Clock.fixed(NOW, ZoneOffset.UTC)
        );
        Season season = fixture().season();

        recorder.recordSeason(season);

        if (capture && metadata) {
            verify(outbox).appendSeasonMetadataIfChanged(season.getId(), season.getName(), NOW);
        } else {
            verifyNoInteractions(outbox);
        }
    }

    @DisplayName("캡처를 켜면 회차와 실제 마감이 있는 실행만 같은 발생 시각으로 적재한다")
    @Test
    void recordsRoundAndScheduledExecution() {
        CalendarOutboxPort outboxPort = mock(CalendarOutboxPort.class);
        CalendarSnapshotRecorder recorder = new CalendarSnapshotRecorder(
                outboxPort,
                new CalendarCaptureState(true, false),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
        Fixture fixture = fixture();

        recorder.record(
                fixture.season(),
                fixture.round(),
                List.of(fixture.scheduledExecution(), fixture.unscheduledExecution())
        );

        ArgumentCaptor<CalendarSnapshotDraft> snapshots =
                ArgumentCaptor.forClass(CalendarSnapshotDraft.class);
        verify(outboxPort, times(2)).appendIfChanged(snapshots.capture());
        assertThat(snapshots.getAllValues())
                .extracting(CalendarSnapshotDraft::sourceItemId)
                .containsExactly(fixture.round().getId(), fixture.scheduledExecution().getId());
        assertThat(snapshots.getAllValues())
                .extracting(CalendarSnapshotDraft::occurredAt)
                .containsOnly(NOW);
    }

    @DisplayName("캡처를 끄면 스냅샷을 만들지 않는다")
    @Test
    void skipsWhenCaptureIsDisabled() {
        CalendarOutboxPort outboxPort = mock(CalendarOutboxPort.class);
        CalendarSnapshotRecorder recorder = new CalendarSnapshotRecorder(
                outboxPort,
                new CalendarCaptureState(false, false),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
        Fixture fixture = fixture();

        recorder.record(fixture.season(), fixture.round(), List.of(fixture.scheduledExecution()));

        verify(outboxPort, never()).appendIfChanged(any());
    }

    private Fixture fixture() {
        UUID seasonId = UUID.randomUUID();
        Season season = Season.create(
                seasonId,
                UUID.randomUUID(),
                "캘린더 시즌",
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 31)
        );
        SeasonRound round = SeasonRound.create(
                UUID.randomUUID(),
                seasonId,
                "8월 회차",
                LocalDate.of(2026, 8, 25)
        );
        return new Fixture(
                season,
                round,
                execution(round, season, -1, LocalTime.of(20, 0)),
                execution(round, season, null, null)
        );
    }

    private RoutineExecution execution(
            SeasonRound round,
            Season season,
            Integer dayOffset,
            LocalTime deadlineTime
    ) {
        Routine routine = Routine.create(
                UUID.randomUUID(),
                season.getId(),
                "준비 마감",
                RoutinePhase.BEFORE,
                "준비",
                UUID.randomUUID(),
                "준비 내용을 확인합니다",
                dayOffset,
                deadlineTime
        );
        return RoutineExecution.snapshot(
                UUID.randomUUID(),
                round.getId(),
                routine,
                round.getMeetingDate(),
                season.getZoneId()
        );
    }

    private record Fixture(
            Season season,
            SeasonRound round,
            RoutineExecution scheduledExecution,
            RoutineExecution unscheduledExecution
    ) {
    }
}
