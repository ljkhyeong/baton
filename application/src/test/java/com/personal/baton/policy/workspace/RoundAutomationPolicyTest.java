package com.personal.baton.policy.workspace;

import com.personal.baton.domain.workspace.DomainValidationException;
import com.personal.baton.domain.workspace.RoundOrigin;
import com.personal.baton.domain.workspace.RoundRecurrence;
import com.personal.baton.domain.workspace.RoundSchedule;
import com.personal.baton.domain.workspace.Routine;
import com.personal.baton.domain.workspace.RoutineExecution;
import com.personal.baton.domain.workspace.RoutinePhase;
import com.personal.baton.domain.workspace.RoutineTimingStatus;
import com.personal.baton.domain.workspace.Season;
import com.personal.baton.domain.workspace.SeasonRound;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("policy")
class RoundAutomationPolicyTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @DisplayName("회차 일정은 사전 생성 시작일에 도달하면 한 건을 열고 주기만큼 커서를 전진한다")
    @Test
    void opensOccurrenceAtLeadBoundaryAndAdvancesCursor() {
        Season season = activeSeason();
        season.configureRoundSchedule(
                LocalDate.of(2026, 8, 10),
                LocalTime.of(19, 30),
                RoundRecurrence.WEEKLY,
                7
        );

        assertThat(season.nextDueRoundOccurrence(LocalDate.of(2026, 8, 2))).isEmpty();
        assertThat(season.nextDueRoundOccurrence(LocalDate.of(2026, 8, 3)))
                .contains(LocalDate.of(2026, 8, 10));

        assertThat(season.advanceRoundSchedule()).isEqualTo(LocalDate.of(2026, 8, 17));
        assertThat(season.nextDueRoundOccurrence(LocalDate.of(2026, 8, 9))).isEmpty();
        assertThat(season.nextDueRoundOccurrence(LocalDate.of(2026, 8, 10)))
                .contains(LocalDate.of(2026, 8, 17));
    }

    @DisplayName("격주 일정은 14일 간격으로 전진하고 시즌 종료일을 지난 커서는 생성하지 않는다")
    @Test
    void advancesBiweeklyScheduleAndStopsAfterSeasonEnd() {
        Season season = activeSeason();
        season.configureRoundSchedule(
                LocalDate.of(2026, 8, 17),
                LocalTime.of(20, 0),
                RoundRecurrence.BIWEEKLY,
                0
        );

        assertThat(season.advanceRoundSchedule()).isEqualTo(LocalDate.of(2026, 8, 31));
        assertThat(season.advanceRoundSchedule()).isEqualTo(LocalDate.of(2026, 9, 14));

        assertThat(season.nextDueRoundOccurrence(LocalDate.of(2026, 9, 14))).isEmpty();
    }

    @DisplayName("비활성 또는 종료된 시즌의 회차 일정은 자동 생성 대상으로 열리지 않는다")
    @Test
    void skipsDisabledAndEndedSchedules() {
        Season season = activeSeason();
        season.configureRoundSchedule(
                LocalDate.of(2026, 8, 10),
                LocalTime.of(19, 30),
                RoundRecurrence.WEEKLY,
                7
        );

        season.disableRoundSchedule();

        assertThat(season.nextDueRoundOccurrence(LocalDate.of(2026, 8, 10))).isEmpty();

        season.enableRoundSchedule();
        season.updateEnding(true, Instant.parse("2026-08-01T00:00:00Z"));

        assertThat(season.nextDueRoundOccurrence(LocalDate.of(2026, 8, 10))).isEmpty();
        assertThatThrownBy(season::enableRoundSchedule)
                .isInstanceOf(DomainValidationException.class);
    }

    @DisplayName("회차 일정은 시즌 밖의 첫 날짜와 주기에 맞지 않는 커서를 거부한다")
    @Test
    void rejectsInvalidScheduleDatesAndCursor() {
        Season season = activeSeason();

        assertThatThrownBy(() -> season.configureRoundSchedule(
                LocalDate.of(2026, 9, 1),
                LocalTime.of(19, 30),
                RoundRecurrence.WEEKLY,
                7
        )).isInstanceOf(DomainValidationException.class);

        assertThatThrownBy(() -> season.configureRoundSchedule(
                LocalDate.of(2026, 8, 10),
                LocalTime.of(19, 30),
                RoundRecurrence.WEEKLY,
                7,
                true,
                LocalDate.of(2026, 8, 18)
        )).isInstanceOf(DomainValidationException.class);
    }

    @DisplayName("회차 일정 변경은 이미 전진한 커서를 되감지 않고 새 반복 주기의 다음 날짜로 정렬한다")
    @Test
    void reconfiguresScheduleWithoutRewindingCursor() {
        Season season = activeSeason();
        season.configureRoundSchedule(
                LocalDate.of(2026, 8, 10),
                LocalTime.of(19, 30),
                RoundRecurrence.WEEKLY,
                7
        );
        season.advanceRoundSchedule();
        season.advanceRoundSchedule();

        season.configureRoundSchedule(
                LocalDate.of(2026, 8, 11),
                LocalTime.of(20, 0),
                RoundRecurrence.BIWEEKLY,
                3,
                false
        );

        assertThat(season.getRoundSchedule().getNextOccurrenceDate())
                .isEqualTo(LocalDate.of(2026, 8, 25));
        assertThat(season.getRoundSchedule().isEnabled()).isFalse();
    }

    @DisplayName("회차 커서 정렬은 정확한 반복 주기 경계와 그 다음 날을 구분한다")
    @Test
    void alignsOccurrenceAtCeilingBoundaries() {
        LocalDate firstMeetingDate = LocalDate.of(2026, 8, 10);

        assertThat(RoundSchedule.occurrenceOnOrAfter(
                firstMeetingDate,
                RoundRecurrence.BIWEEKLY,
                firstMeetingDate.plusDays(14)
        )).isEqualTo(firstMeetingDate.plusDays(14));
        assertThat(RoundSchedule.occurrenceOnOrAfter(
                firstMeetingDate,
                RoundRecurrence.BIWEEKLY,
                firstMeetingDate.plusDays(15)
        )).isEqualTo(firstMeetingDate.plusDays(28));
    }

    @DisplayName("시즌 시간대는 IANA 식별자로 정규화하고 잘못된 식별자는 거부한다")
    @Test
    void validatesIanaTimeZone() {
        Season season = activeSeason();

        season.updateTimeZone("Etc/UTC");

        assertThat(season.getTimeZone()).isEqualTo("Etc/UTC");
        assertThat(season.getZoneId()).isEqualTo(ZoneId.of("Etc/UTC"));

        season.updateTimeZone("CET");

        assertThat(season.getTimeZone()).isEqualTo("CET");
        assertThat(season.getZoneId()).isEqualTo(ZoneId.of("CET"));
        assertThatThrownBy(() -> season.updateTimeZone("Mars/Olympus"))
                .isInstanceOf(DomainValidationException.class);
        assertThat(season.getTimeZone()).isEqualTo("CET");
    }

    @DisplayName("시즌 시간대는 오프셋과 UTC 단일 별칭을 IANA 지역 식별자로 허용하지 않는다")
    @Test
    void rejectsOffsetAndUtcAliasesAsSeasonTimeZone() {
        Season season = activeSeason();

        for (String unsupportedTimeZone : new String[]{"+09:00", "Z", "UTC+09:00", "UTC"}) {
            assertThatThrownBy(() -> season.updateTimeZone(unsupportedTimeZone))
                    .isInstanceOf(DomainValidationException.class)
                    .hasMessage("유효한 IANA 시간대가 아닙니다");
        }

        assertThat(season.getTimeZone()).isEqualTo("Asia/Seoul");
    }

    @DisplayName("루틴 마감 규칙은 날짜 오프셋과 시각을 함께 검증하고 다음 시즌 복사에도 보존한다")
    @Test
    void validatesAndCopiesRoutineDeadlineRule() {
        UUID sourceRoutineId = UUID.randomUUID();
        Routine routine = routine(sourceRoutineId, -1, LocalTime.of(23, 0));

        routine.update(
                "정기 결과 보고",
                RoutinePhase.AFTER,
                "모임 다음 날",
                UUID.randomUUID(),
                "결과 보고서를 공유한다"
        );
        Routine copied = routine.copyToSeason(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID()
        );

        assertThat(copied.getPreviousRoutineId()).isEqualTo(sourceRoutineId);
        assertThat(copied.getDeadlineDayOffset()).isEqualTo(-1);
        assertThat(copied.getDeadlineTime()).isEqualTo(LocalTime.of(23, 0));
        assertThatThrownBy(() -> routine.updateDeadlineRule(1, null))
                .isInstanceOf(DomainValidationException.class);
        assertThat(routine.getDeadlineDayOffset()).isEqualTo(-1);
        assertThat(routine.getDeadlineTime()).isEqualTo(LocalTime.of(23, 0));
    }

    @DisplayName("루틴 실행은 마감일 전·마감일 진행 중·마감 시각부터 지연을 구분한다")
    @Test
    void distinguishesPlannedInProgressAndOverdueBoundaries() {
        RoutineExecution execution = RoutineExecution.snapshot(
                UUID.randomUUID(),
                UUID.randomUUID(),
                routine(UUID.randomUUID(), -1, LocalTime.of(18, 0)),
                LocalDate.of(2026, 8, 10),
                SEOUL
        );

        assertThat(execution.getDeadlineAt()).isEqualTo(Instant.parse("2026-08-09T09:00:00Z"));
        assertThat(execution.timingStatus(
                fixedClock("2026-08-08T14:59:59Z"),
                SEOUL
        )).isEqualTo(RoutineTimingStatus.PLANNED);
        assertThat(execution.timingStatus(
                fixedClock("2026-08-08T15:00:00Z"),
                SEOUL
        )).isEqualTo(RoutineTimingStatus.IN_PROGRESS);
        assertThat(execution.timingStatus(
                fixedClock("2026-08-09T09:00:00Z"),
                SEOUL
        )).isEqualTo(RoutineTimingStatus.OVERDUE);

        execution.reschedule(LocalDate.of(2026, 8, 17), SEOUL);

        assertThat(execution.getDeadlineAt()).isEqualTo(Instant.parse("2026-08-16T09:00:00Z"));
    }

    @DisplayName("완료 상태는 마감 지연보다 우선하고 마감 규칙이 없으면 미지정으로 판정한다")
    @Test
    void prioritizesCompletionAndReportsUnscheduledRule() {
        Routine scheduledRoutine = routine(UUID.randomUUID(), 0, LocalTime.NOON);
        RoutineExecution scheduled = RoutineExecution.snapshot(
                UUID.randomUUID(),
                UUID.randomUUID(),
                scheduledRoutine,
                LocalDate.of(2026, 8, 10),
                SEOUL
        );
        RoutineExecution unscheduled = RoutineExecution.snapshot(
                UUID.randomUUID(),
                UUID.randomUUID(),
                routine(UUID.randomUUID(), null, null)
        );

        scheduled.updateCompletion(true);

        assertThat(scheduled.timingStatus(
                fixedClock("2026-08-12T00:00:00Z"),
                SEOUL
        )).isEqualTo(RoutineTimingStatus.COMPLETED);
        assertThat(unscheduled.timingStatus(
                fixedClock("2026-08-12T00:00:00Z"),
                SEOUL
        )).isEqualTo(RoutineTimingStatus.UNSCHEDULED);
        assertThatThrownBy(() -> RoutineExecution.snapshot(
                UUID.randomUUID(),
                UUID.randomUUID(),
                scheduledRoutine
        )).isInstanceOf(DomainValidationException.class);
    }

    @DisplayName("DST 공백과 중복 시각은 Java atZone의 전진 및 앞선 오프셋 정책으로 고정한다")
    @Test
    void resolvesDstGapAndOverlapWithJavaAtZonePolicy() {
        ZoneId newYork = ZoneId.of("America/New_York");
        Routine deadlineAtOneThirty = routine(UUID.randomUUID(), 0, LocalTime.of(1, 30));
        Routine deadlineAtTwoThirty = routine(UUID.randomUUID(), 0, LocalTime.of(2, 30));

        RoutineExecution gap = RoutineExecution.snapshot(
                UUID.randomUUID(),
                UUID.randomUUID(),
                deadlineAtTwoThirty,
                LocalDate.of(2026, 3, 8),
                newYork
        );
        RoutineExecution overlap = RoutineExecution.snapshot(
                UUID.randomUUID(),
                UUID.randomUUID(),
                deadlineAtOneThirty,
                LocalDate.of(2026, 11, 1),
                newYork
        );

        assertThat(gap.getDeadlineAt()).isEqualTo(Instant.parse("2026-03-08T07:30:00Z"));
        assertThat(overlap.getDeadlineAt()).isEqualTo(Instant.parse("2026-11-01T05:30:00Z"));
    }

    @DisplayName("자동 회차는 생성 출처와 중복 방지 예정일 및 시간대가 반영된 예정 시각을 보존한다")
    @Test
    void preservesAutomaticRoundMetadata() {
        LocalDate occurrenceDate = LocalDate.of(2026, 8, 10);
        Instant scheduledAt = occurrenceDate
                .atTime(19, 30)
                .atZone(SEOUL)
                .toInstant();

        SeasonRound round = SeasonRound.createAutomatic(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "2026-08-10 회차",
                occurrenceDate,
                scheduledAt
        );

        assertThat(round.getOrigin()).isEqualTo(RoundOrigin.AUTOMATIC);
        assertThat(round.getMeetingDate()).isEqualTo(occurrenceDate);
        assertThat(round.getScheduledOccurrenceDate()).isEqualTo(occurrenceDate);
        assertThat(round.getScheduledAt()).isEqualTo(scheduledAt);
    }

    @DisplayName("자동 회차는 도메인 경계에서도 이름과 날짜를 수정할 수 없다")
    @Test
    void rejectsAutomaticRoundUpdateAtDomainBoundary() {
        LocalDate occurrenceDate = LocalDate.of(2026, 8, 10);
        SeasonRound round = SeasonRound.createAutomatic(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "2026-08-10 회차",
                occurrenceDate,
                occurrenceDate.atTime(19, 30).atZone(SEOUL).toInstant()
        );

        assertThatThrownBy(() -> round.update("변경한 회차", occurrenceDate.plusDays(1)))
                .isInstanceOf(DomainValidationException.class)
                .hasMessage("자동 생성된 회차의 날짜와 이름은 수정할 수 없습니다");
    }

    private static Season activeSeason() {
        return Season.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "여름 시즌",
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 8, 31)
        );
    }

    private static Routine routine(UUID id, Integer deadlineDayOffset, LocalTime deadlineTime) {
        return Routine.create(
                id,
                UUID.randomUUID(),
                "정기 보고",
                RoutinePhase.BEFORE,
                "모임 전",
                UUID.randomUUID(),
                "보고서를 준비한다",
                deadlineDayOffset,
                deadlineTime
        );
    }

    private static Clock fixedClock(String instant) {
        return Clock.fixed(Instant.parse(instant), ZoneId.of("UTC"));
    }
}
