package com.personal.baton.domain.workspace;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "routines")
public class Routine {

    @Id
    @Column(nullable = false, columnDefinition = "binary(16)")
    private UUID id;

    @Column(name = "season_id", nullable = false, columnDefinition = "binary(16)")
    private UUID seasonId;

    @Column(nullable = false, length = 200)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RoutinePhase phase;

    @Column(name = "due_label", nullable = false, length = 100)
    private String dueLabel;

    @Column(name = "owner_role_id", nullable = false, columnDefinition = "binary(16)")
    private UUID ownerRoleId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RoutineStatus status;

    @Column(nullable = false, length = 1000)
    private String detail;

    protected Routine() {
    }

    private Routine(
            UUID id,
            UUID seasonId,
            String title,
            RoutinePhase phase,
            String dueLabel,
            UUID ownerRoleId,
            RoutineStatus status,
            String detail
    ) {
        this.id = Objects.requireNonNull(id, "루틴 식별자는 필수입니다");
        this.seasonId = Objects.requireNonNull(seasonId, "시즌 식별자는 필수입니다");
        this.title = DomainAssertions.requiredText(title, "루틴 제목", 200);
        this.phase = Objects.requireNonNull(phase, "루틴 단계는 필수입니다");
        this.dueLabel = DomainAssertions.requiredText(dueLabel, "루틴 기한 문구", 100);
        this.ownerRoleId = Objects.requireNonNull(ownerRoleId, "담당 역할은 필수입니다");
        this.status = Objects.requireNonNull(status, "루틴 상태는 필수입니다");
        this.detail = DomainAssertions.requiredText(detail, "루틴 상세", 1000);
    }

    public static Routine create(
            UUID id,
            UUID seasonId,
            String title,
            RoutinePhase phase,
            String dueLabel,
            UUID ownerRoleId,
            RoutineStatus status,
            String detail
    ) {
        return new Routine(id, seasonId, title, phase, dueLabel, ownerRoleId, status, detail);
    }

    public void updateCompletion(boolean completed) {
        status = completed ? RoutineStatus.DONE : RoutineStatus.WAITING;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSeasonId() {
        return seasonId;
    }

    public String getTitle() {
        return title;
    }

    public RoutinePhase getPhase() {
        return phase;
    }

    public String getDueLabel() {
        return dueLabel;
    }

    public UUID getOwnerRoleId() {
        return ownerRoleId;
    }

    public RoutineStatus getStatus() {
        return status;
    }

    public String getDetail() {
        return detail;
    }
}
