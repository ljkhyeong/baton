package com.personal.baton.domain.workspace;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
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
}
