package com.personal.baton.domain.workspace;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(
        name = "routine_executions",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_routine_executions_round_routine",
                columnNames = {"season_round_id", "routine_id"}
        )
)
public class RoutineExecution {

    @Id
    @Column(nullable = false, columnDefinition = "binary(16)")
    private UUID id;

    @Column(name = "season_round_id", nullable = false, columnDefinition = "binary(16)")
    private UUID seasonRoundId;

    @Column(name = "routine_id", nullable = false, columnDefinition = "binary(16)")
    private UUID routineId;

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

    @Version
    @Column(nullable = false)
    private Long version;

    protected RoutineExecution() {
    }

    private RoutineExecution(UUID id, UUID seasonRoundId, Routine routine) {
        this.id = Objects.requireNonNull(id, "루틴 실행 식별자는 필수입니다");
        this.seasonRoundId = Objects.requireNonNull(seasonRoundId, "회차 식별자는 필수입니다");
        Routine source = Objects.requireNonNull(routine, "스냅샷할 루틴은 필수입니다");
        this.routineId = source.getId();
        this.title = source.getTitle();
        this.phase = source.getPhase();
        this.dueLabel = source.getDueLabel();
        this.ownerRoleId = source.getOwnerRoleId();
        this.status = RoutineStatus.WAITING;
        this.detail = source.getDetail();
    }

    public static RoutineExecution snapshot(UUID id, UUID seasonRoundId, Routine routine) {
        return new RoutineExecution(id, seasonRoundId, routine);
    }

    public void updateCompletion(boolean completed) {
        status = completed ? RoutineStatus.DONE : RoutineStatus.WAITING;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSeasonRoundId() {
        return seasonRoundId;
    }

    public UUID getRoutineId() {
        return routineId;
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
