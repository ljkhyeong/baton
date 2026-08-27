package com.personal.baton.domain.workspace;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(
        name = "seasons",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_seasons_team_name", columnNames = {"team_id", "name"}),
                @UniqueConstraint(name = "uk_seasons_previous_season", columnNames = "previous_season_id")
        }
)
public class Season {

    public static final String DEFAULT_TIME_ZONE = "Asia/Seoul";
    private static final Set<String> AVAILABLE_TIME_ZONE_IDS = ZoneId.getAvailableZoneIds();
    private static final Set<String> UNSUPPORTED_TIME_ZONE_ALIASES = Set.of("UTC");

    @Id
    @Column(nullable = false, columnDefinition = "binary(16)")
    private UUID id;

    @Column(name = "team_id", nullable = false, columnDefinition = "binary(16)")
    private UUID teamId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "previous_season_id", columnDefinition = "binary(16)")
    private UUID previousSeasonId;

    @Column(name = "time_zone", nullable = false, length = 64)
    private String timeZone;

    @Embedded
    private RoundSchedule roundSchedule;

    @Version
    @Column(nullable = false)
    private Long version;

    protected Season() {
    }

    private Season(
            UUID id,
            UUID teamId,
            String name,
            LocalDate startDate,
            LocalDate endDate,
            UUID previousSeasonId,
            String timeZone
    ) {
        this.id = Objects.requireNonNull(id, "시즌 식별자는 필수입니다");
        this.teamId = Objects.requireNonNull(teamId, "팀 식별자는 필수입니다");
        this.previousSeasonId = previousSeasonId;
        this.timeZone = normalizeTimeZone(timeZone);
        update(name, startDate, endDate);
    }

    public static Season create(UUID id, UUID teamId, String name, LocalDate startDate, LocalDate endDate) {
        return create(id, teamId, name, startDate, endDate, DEFAULT_TIME_ZONE);
    }

    public static Season create(
            UUID id,
            UUID teamId,
            String name,
            LocalDate startDate,
            LocalDate endDate,
            String timeZone
    ) {
        return new Season(id, teamId, name, startDate, endDate, null, timeZone);
    }

    public static Season createSuccessor(
            UUID id,
            UUID teamId,
            UUID previousSeasonId,
            String name,
            LocalDate startDate,
            LocalDate endDate,
            String timeZone
    ) {
        return new Season(
                id,
                teamId,
                name,
                startDate,
                endDate,
                Objects.requireNonNull(previousSeasonId, "이전 시즌 식별자는 필수입니다"),
                timeZone
        );
    }

    private static String normalizeName(String name) {
        return DomainAssertions.requiredText(name, "시즌 이름", 100);
    }

    public void update(String name, LocalDate startDate, LocalDate endDate) {
        String normalizedName = normalizeName(name);
        LocalDate validatedStartDate = Objects.requireNonNull(startDate, "시즌 시작일은 필수입니다");
        LocalDate validatedEndDate = Objects.requireNonNull(endDate, "시즌 종료일은 필수입니다");
        if (validatedStartDate.isAfter(validatedEndDate)) {
            throw new DomainValidationException("시즌 시작일은 종료일보다 늦을 수 없습니다");
        }
        if (roundSchedule != null
                && (roundSchedule.getFirstMeetingDate().isBefore(validatedStartDate)
                || roundSchedule.getFirstMeetingDate().isAfter(validatedEndDate))) {
            throw new DomainValidationException("첫 모임 날짜는 시즌 기간 안에 있어야 합니다");
        }
        this.name = normalizedName;
        this.startDate = validatedStartDate;
        this.endDate = validatedEndDate;
    }

    public void updateEnding(boolean ended, Instant now) {
        if (!ended) {
            endedAt = null;
            return;
        }
        if (endedAt == null) {
            endedAt = Objects.requireNonNull(now, "시즌 종료 시각은 필수입니다");
        }
    }

    public void updateTimeZone(String timeZone) {
        this.timeZone = normalizeTimeZone(timeZone);
    }

    public void configureRoundSchedule(
            LocalDate firstMeetingDate,
            LocalTime meetingTime,
            RoundRecurrence recurrence,
            int generationLeadDays,
            boolean enabled
    ) {
        LocalDate cursorLowerBound = roundSchedule == null
                ? firstMeetingDate
                : roundSchedule.getNextOccurrenceDate();
        LocalDate nextOccurrenceDate = RoundSchedule.occurrenceOnOrAfter(
                firstMeetingDate,
                recurrence,
                cursorLowerBound
        );
        configureRoundSchedule(
                firstMeetingDate,
                meetingTime,
                recurrence,
                generationLeadDays,
                enabled,
                nextOccurrenceDate
        );
    }

    public void configureRoundSchedule(
            LocalDate firstMeetingDate,
            LocalTime meetingTime,
            RoundRecurrence recurrence,
            int generationLeadDays,
            boolean enabled,
            LocalDate nextOccurrenceDate
    ) {
        requireActiveForScheduling();
        LocalDate validatedFirstMeetingDate =
                Objects.requireNonNull(firstMeetingDate, "첫 모임 날짜는 필수입니다");
        if (!contains(validatedFirstMeetingDate)) {
            throw new DomainValidationException("첫 모임 날짜는 시즌 기간 안에 있어야 합니다");
        }
        roundSchedule = RoundSchedule.configure(
                validatedFirstMeetingDate,
                meetingTime,
                recurrence,
                generationLeadDays,
                enabled,
                nextOccurrenceDate
        );
    }

    public void disableRoundSchedule() {
        requireRoundSchedule().disable();
    }

    public Optional<LocalDate> nextDueRoundOccurrence(LocalDate today) {
        if (isEnded() || roundSchedule == null) {
            return Optional.empty();
        }
        return roundSchedule.nextDueOccurrence(today, endDate);
    }

    public LocalDate advanceRoundSchedule() {
        return requireRoundSchedule().advance();
    }

    private void requireActiveForScheduling() {
        if (isEnded()) {
            throw new DomainValidationException("종료된 시즌의 회차 일정을 변경할 수 없습니다");
        }
    }

    private RoundSchedule requireRoundSchedule() {
        if (roundSchedule == null) {
            throw new DomainValidationException("회차 일정이 설정되지 않았습니다");
        }
        return roundSchedule;
    }

    public static String normalizeTimeZone(String timeZone) {
        String normalized = DomainAssertions.requiredText(timeZone, "시간대", 64);
        if (!AVAILABLE_TIME_ZONE_IDS.contains(normalized)
                || UNSUPPORTED_TIME_ZONE_ALIASES.contains(normalized)) {
            throw new DomainValidationException("유효한 IANA 시간대가 아닙니다");
        }
        return normalized;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTeamId() {
        return teamId;
    }

    public String getName() {
        return name;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public Instant getEndedAt() {
        return endedAt;
    }

    public UUID getPreviousSeasonId() {
        return previousSeasonId;
    }

    public String getTimeZone() {
        return timeZone;
    }

    public ZoneId getZoneId() {
        return ZoneId.of(timeZone);
    }

    public RoundSchedule getRoundSchedule() {
        return roundSchedule;
    }

    public boolean isEnded() {
        return endedAt != null;
    }

    public boolean contains(LocalDate date) {
        LocalDate validatedDate = Objects.requireNonNull(date, "날짜는 필수입니다");
        return !validatedDate.isBefore(startDate) && !validatedDate.isAfter(endDate);
    }
}
