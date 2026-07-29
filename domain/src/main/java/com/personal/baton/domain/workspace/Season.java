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
        name = "seasons",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_seasons_team_name", columnNames = {"team_id", "name"}),
                @UniqueConstraint(name = "uk_seasons_previous_season", columnNames = "previous_season_id")
        }
)
public class Season {

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
            UUID previousSeasonId
    ) {
        this.id = Objects.requireNonNull(id, "시즌 식별자는 필수입니다");
        this.teamId = Objects.requireNonNull(teamId, "팀 식별자는 필수입니다");
        this.previousSeasonId = previousSeasonId;
        update(name, startDate, endDate);
    }

    public static Season create(UUID id, UUID teamId, String name, LocalDate startDate, LocalDate endDate) {
        return new Season(id, teamId, name, startDate, endDate, null);
    }

    public static Season createSuccessor(
            UUID id,
            UUID teamId,
            UUID previousSeasonId,
            String name,
            LocalDate startDate,
            LocalDate endDate
    ) {
        return new Season(
                id,
                teamId,
                name,
                startDate,
                endDate,
                Objects.requireNonNull(previousSeasonId, "이전 시즌 식별자는 필수입니다")
        );
    }

    public static String normalizeName(String name) {
        return DomainAssertions.requiredText(name, "시즌 이름", 100);
    }

    public void update(String name, LocalDate startDate, LocalDate endDate) {
        String normalizedName = normalizeName(name);
        LocalDate validatedStartDate = Objects.requireNonNull(startDate, "시즌 시작일은 필수입니다");
        LocalDate validatedEndDate = Objects.requireNonNull(endDate, "시즌 종료일은 필수입니다");
        if (validatedStartDate.isAfter(validatedEndDate)) {
            throw new DomainValidationException("시즌 시작일은 종료일보다 늦을 수 없습니다");
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

    public boolean isEnded() {
        return endedAt != null;
    }

    public boolean contains(LocalDate date) {
        LocalDate validatedDate = Objects.requireNonNull(date, "날짜는 필수입니다");
        return !validatedDate.isBefore(startDate) && !validatedDate.isAfter(endDate);
    }
}
