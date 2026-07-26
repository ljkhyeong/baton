package com.personal.baton.domain.workspace;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(
        name = "season_rounds",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_season_rounds_season_name",
                columnNames = {"season_id", "name"}
        )
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

    @Column(name = "archived_at")
    private Instant archivedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected SeasonRound() {
    }

    private SeasonRound(UUID id, UUID seasonId, String name, LocalDate meetingDate) {
        this.id = Objects.requireNonNull(id, "회차 식별자는 필수입니다");
        this.seasonId = Objects.requireNonNull(seasonId, "시즌 식별자는 필수입니다");
        this.name = DomainAssertions.requiredText(name, "회차 이름", 100);
        this.meetingDate = Objects.requireNonNull(meetingDate, "모임 날짜는 필수입니다");
    }

    public static SeasonRound create(UUID id, UUID seasonId, String name, LocalDate meetingDate) {
        return new SeasonRound(id, seasonId, name, meetingDate);
    }

    public static String normalizeName(String name) {
        return DomainAssertions.requiredText(name, "회차 이름", 100);
    }

    public void update(String name, LocalDate meetingDate) {
        requireActive();
        String normalizedName = normalizeName(name);
        LocalDate normalizedMeetingDate = Objects.requireNonNull(meetingDate, "모임 날짜는 필수입니다");
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

    public Instant getArchivedAt() {
        return archivedAt;
    }
}
