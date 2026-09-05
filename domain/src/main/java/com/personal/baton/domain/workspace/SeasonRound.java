package com.personal.baton.domain.workspace;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(
        name = "season_rounds",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_season_rounds_season_name",
                        columnNames = {"season_id", "name"}
                ),
                @UniqueConstraint(
                        name = "uk_season_rounds_season_occurrence",
                        columnNames = {"season_id", "scheduled_occurrence_date"}
                )
        }
)
public class SeasonRound {

    @Id
    @Column(nullable = false, columnDefinition = "binary(16)")
    private UUID id;

    @Column(name = "season_id", nullable = false, columnDefinition = "binary(16)")
    private UUID seasonId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "meeting_date")
    private LocalDate meetingDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RoundOrigin origin;

    @Column(name = "scheduled_occurrence_date")
    private LocalDate scheduledOccurrenceDate;

    @Column(name = "scheduled_at")
    private Instant scheduledAt;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @Version
    @Column(nullable = false)
    private Long version;

    protected SeasonRound() {
    }

    private SeasonRound(
            UUID id,
            UUID seasonId,
            String name,
            LocalDate meetingDate,
            RoundOrigin origin,
            LocalDate scheduledOccurrenceDate,
            Instant scheduledAt
    ) {
        this.id = Objects.requireNonNull(id, "회차 식별자는 필수입니다");
        this.seasonId = Objects.requireNonNull(seasonId, "시즌 식별자는 필수입니다");
        this.name = DomainAssertions.requiredText(name, "회차 이름", 100);
        this.meetingDate = Objects.requireNonNull(meetingDate, "모임 날짜는 필수입니다");
        this.origin = Objects.requireNonNull(origin, "회차 생성 출처는 필수입니다");
        this.scheduledOccurrenceDate = scheduledOccurrenceDate;
        this.scheduledAt = scheduledAt;
        validateScheduleMetadata();
    }

    public static SeasonRound create(UUID id, UUID seasonId, String name, LocalDate meetingDate) {
        return new SeasonRound(
                id,
                seasonId,
                name,
                meetingDate,
                RoundOrigin.MANUAL,
                null,
                null
        );
    }

    public static SeasonRound createAutomatic(
            UUID id,
            UUID seasonId,
            String name,
            LocalDate scheduledOccurrenceDate,
            Instant scheduledAt
    ) {
        LocalDate validatedOccurrenceDate =
                Objects.requireNonNull(scheduledOccurrenceDate, "자동 회차 예정일은 필수입니다");
        return new SeasonRound(
                id,
                seasonId,
                name,
                validatedOccurrenceDate,
                RoundOrigin.AUTOMATIC,
                validatedOccurrenceDate,
                Objects.requireNonNull(scheduledAt, "자동 회차 예정 시각은 필수입니다")
        );
    }

    private static String normalizeName(String name) {
        return DomainAssertions.requiredText(name, "회차 이름", 100);
    }

    public void update(String name, LocalDate meetingDate, ZoneId timeZone) {
        requireActive();
        String normalizedName = normalizeName(name);
        LocalDate normalizedMeetingDate = Objects.requireNonNull(meetingDate, "모임 날짜는 필수입니다");
        if (origin == RoundOrigin.AUTOMATIC && !normalizedMeetingDate.equals(this.meetingDate)) {
            // 원래 발생일은 중복 생성 방지에 남기고, 저장된 현지 모임 시각을 새 날짜로 옮긴다.
            this.scheduledAt = normalizedMeetingDate
                    .atTime(scheduledAt.atZone(timeZone).toLocalTime())
                    .atZone(timeZone)
                    .toInstant();
        }
        this.name = normalizedName;
        this.meetingDate = normalizedMeetingDate;
    }

    public void updateArchive(boolean archived, Instant archivedAt) {
        if (archived) {
            if (this.archivedAt == null) {
                this.archivedAt = Objects.requireNonNull(archivedAt, "회차 보관 시각은 필수입니다");
            }
            return;
        }
        this.archivedAt = null;
    }

    private void requireActive() {
        if (archivedAt != null) {
            throw new DomainValidationException("보관된 회차는 수정할 수 없습니다");
        }
    }

    private void validateScheduleMetadata() {
        boolean automatic = origin == RoundOrigin.AUTOMATIC;
        if (automatic != (scheduledOccurrenceDate != null && scheduledAt != null)) {
            throw new DomainValidationException("자동 회차에만 예정 날짜와 예정 시각을 설정할 수 있습니다");
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getSeasonId() {
        return seasonId;
    }

    public String getName() {
        return name;
    }

    public LocalDate getMeetingDate() {
        return meetingDate;
    }

    public RoundOrigin getOrigin() {
        return origin;
    }

    public LocalDate getScheduledOccurrenceDate() {
        return scheduledOccurrenceDate;
    }

    public Instant getScheduledAt() {
        return scheduledAt;
    }

    public Instant getArchivedAt() {
        return archivedAt;
    }
}
