package com.personal.baton.domain.workspace;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;

@Embeddable
public class RoundSchedule {

    public static final int MIN_GENERATION_LEAD_DAYS = 0;
    public static final int MAX_GENERATION_LEAD_DAYS = 30;

    @Column(name = "round_schedule_first_meeting_date")
    private LocalDate firstMeetingDate;

    @Column(name = "round_schedule_meeting_time")
    private LocalTime meetingTime;

    @Enumerated(EnumType.STRING)
    @Column(name = "round_schedule_recurrence", length = 16)
    private RoundRecurrence recurrence;

    @Column(name = "round_schedule_generation_lead_days")
    private Integer generationLeadDays;

    @Column(name = "round_schedule_enabled")
    private Boolean enabled;

    @Column(name = "round_schedule_next_occurrence_date")
    private LocalDate nextOccurrenceDate;

    protected RoundSchedule() {
    }

    private RoundSchedule(
            LocalDate firstMeetingDate,
            LocalTime meetingTime,
            RoundRecurrence recurrence,
            int generationLeadDays,
            boolean enabled,
            LocalDate nextOccurrenceDate
    ) {
        LocalDate validatedFirstMeetingDate =
                Objects.requireNonNull(firstMeetingDate, "첫 모임 날짜는 필수입니다");
        LocalTime validatedMeetingTime = Objects.requireNonNull(meetingTime, "모임 시각은 필수입니다");
        RoundRecurrence validatedRecurrence = Objects.requireNonNull(recurrence, "회차 반복 주기는 필수입니다");
        int validatedGenerationLeadDays = validateGenerationLeadDays(generationLeadDays);
        LocalDate validatedNextOccurrenceDate =
                Objects.requireNonNull(nextOccurrenceDate, "다음 회차 예정일은 필수입니다");
        validateOccurrenceCursor(
                validatedFirstMeetingDate,
                validatedNextOccurrenceDate,
                validatedRecurrence
        );

        this.firstMeetingDate = validatedFirstMeetingDate;
        this.meetingTime = validatedMeetingTime;
        this.recurrence = validatedRecurrence;
        this.generationLeadDays = validatedGenerationLeadDays;
        this.enabled = enabled;
        this.nextOccurrenceDate = validatedNextOccurrenceDate;
    }

    public static RoundSchedule configure(
            LocalDate firstMeetingDate,
            LocalTime meetingTime,
            RoundRecurrence recurrence,
            int generationLeadDays
    ) {
        return configure(
                firstMeetingDate,
                meetingTime,
                recurrence,
                generationLeadDays,
                true,
                firstMeetingDate
        );
    }

    public static LocalDate occurrenceOnOrAfter(
            LocalDate firstMeetingDate,
            RoundRecurrence recurrence,
            LocalDate lowerBound
    ) {
        LocalDate validatedFirstMeetingDate =
                Objects.requireNonNull(firstMeetingDate, "첫 모임 날짜는 필수입니다");
        RoundRecurrence validatedRecurrence = Objects.requireNonNull(recurrence, "회차 반복 주기는 필수입니다");
        LocalDate validatedLowerBound = Objects.requireNonNull(lowerBound, "회차 커서 하한은 필수입니다");
        if (!validatedLowerBound.isAfter(validatedFirstMeetingDate)) {
            return validatedFirstMeetingDate;
        }
        long daysFromFirstMeeting = ChronoUnit.DAYS.between(
                validatedFirstMeetingDate,
                validatedLowerBound
        );
        long intervalDays = validatedRecurrence.getIntervalDays();
        long intervals = Math.ceilDiv(daysFromFirstMeeting, intervalDays);
        return validatedFirstMeetingDate.plusDays(intervals * intervalDays);
    }

    public static RoundSchedule configure(
            LocalDate firstMeetingDate,
            LocalTime meetingTime,
            RoundRecurrence recurrence,
            int generationLeadDays,
            boolean enabled,
            LocalDate nextOccurrenceDate
    ) {
        return new RoundSchedule(
                firstMeetingDate,
                meetingTime,
                recurrence,
                generationLeadDays,
                enabled,
                nextOccurrenceDate
        );
    }

    public Optional<LocalDate> nextDueOccurrence(LocalDate today, LocalDate seasonEndDate) {
        LocalDate validatedToday = Objects.requireNonNull(today, "기준 날짜는 필수입니다");
        LocalDate validatedSeasonEndDate = Objects.requireNonNull(seasonEndDate, "시즌 종료일은 필수입니다");
        if (!isEnabled() || nextOccurrenceDate.isAfter(validatedSeasonEndDate)) {
            return Optional.empty();
        }
        LocalDate generationOpeningDate = nextOccurrenceDate.minusDays(generationLeadDays);
        if (validatedToday.isBefore(generationOpeningDate)) {
            return Optional.empty();
        }
        return Optional.of(nextOccurrenceDate);
    }

    public LocalDate advance() {
        nextOccurrenceDate = recurrence.next(nextOccurrenceDate);
        return nextOccurrenceDate;
    }

    public void enable() {
        enabled = true;
    }

    public void disable() {
        enabled = false;
    }

    private static int validateGenerationLeadDays(int generationLeadDays) {
        if (generationLeadDays < MIN_GENERATION_LEAD_DAYS
                || generationLeadDays > MAX_GENERATION_LEAD_DAYS) {
            throw new DomainValidationException(
                    "회차 사전 생성 일수는 "
                            + MIN_GENERATION_LEAD_DAYS
                            + "일 이상 "
                            + MAX_GENERATION_LEAD_DAYS
                            + "일 이하여야 합니다"
            );
        }
        return generationLeadDays;
    }

    private static void validateOccurrenceCursor(
            LocalDate firstMeetingDate,
            LocalDate nextOccurrenceDate,
            RoundRecurrence recurrence
    ) {
        if (nextOccurrenceDate.isBefore(firstMeetingDate)) {
            throw new DomainValidationException("다음 회차 예정일은 첫 모임 날짜보다 빠를 수 없습니다");
        }
        long daysFromFirstMeeting = ChronoUnit.DAYS.between(firstMeetingDate, nextOccurrenceDate);
        if (daysFromFirstMeeting % recurrence.getIntervalDays() != 0) {
            throw new DomainValidationException("다음 회차 예정일은 반복 주기와 일치해야 합니다");
        }
    }

    public LocalDate getFirstMeetingDate() {
        return firstMeetingDate;
    }

    public LocalTime getMeetingTime() {
        return meetingTime;
    }

    public RoundRecurrence getRecurrence() {
        return recurrence;
    }

    public int getGenerationLeadDays() {
        return generationLeadDays;
    }

    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled);
    }

    public LocalDate getNextOccurrenceDate() {
        return nextOccurrenceDate;
    }
}
