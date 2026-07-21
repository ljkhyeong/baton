package com.personal.baton.domain.workspace;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "seasons")
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

    protected Season() {
    }

    private Season(UUID id, UUID teamId, String name, LocalDate startDate, LocalDate endDate) {
        this.id = Objects.requireNonNull(id, "시즌 식별자는 필수입니다");
        this.teamId = Objects.requireNonNull(teamId, "팀 식별자는 필수입니다");
        this.name = DomainAssertions.requiredText(name, "시즌 이름", 100);
        this.startDate = Objects.requireNonNull(startDate, "시즌 시작일은 필수입니다");
        this.endDate = Objects.requireNonNull(endDate, "시즌 종료일은 필수입니다");
        if (startDate.isAfter(endDate)) {
            throw new DomainValidationException("시즌 시작일은 종료일보다 늦을 수 없습니다");
        }
    }

    public static Season create(UUID id, UUID teamId, String name, LocalDate startDate, LocalDate endDate) {
        return new Season(id, teamId, name, startDate, endDate);
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

    public boolean contains(LocalDate date) {
        LocalDate validatedDate = Objects.requireNonNull(date, "날짜는 필수입니다");
        return !validatedDate.isBefore(startDate) && !validatedDate.isAfter(endDate);
    }
}
