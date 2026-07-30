package com.personal.baton.domain.workspace;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
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

    @Column(name = "deadline_day_offset")
    private Integer deadlineDayOffset;

    @Column(name = "deadline_time")
    private LocalTime deadlineTime;

    @Column(name = "deadline_at")
    private Instant deadlineAt;

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
        this.deadlineDayOffset = source.getDeadlineDayOffset();
        this.deadlineTime = source.getDeadlineTime();
        this.ownerRoleId = source.getOwnerRoleId();
        this.status = RoutineStatus.WAITING;
        this.detail = source.getDetail();
    }

    public static RoutineExecution snapshot(UUID id, UUID seasonRoundId, Routine routine) {
        RoutineExecution execution = new RoutineExecution(id, seasonRoundId, routine);
        if (execution.deadlineDayOffset != null) {
            throw new DomainValidationException(
                    "마감 규칙이 있는 루틴 실행에는 모임 날짜와 시즌 시간대가 필요합니다"
            );
        }
        return execution;
    }

    public static RoutineExecution snapshot(
            UUID id,
            UUID seasonRoundId,
            Routine routine,
            LocalDate meetingDate,
            ZoneId zoneId
    ) {
        RoutineExecution execution = new RoutineExecution(id, seasonRoundId, routine);
        execution.reschedule(meetingDate, zoneId);
        return execution;
    }

    public void reschedule(LocalDate meetingDate, ZoneId zoneId) {
        LocalDate validatedMeetingDate = Objects.requireNonNull(meetingDate, "모임 날짜는 필수입니다");
        ZoneId validatedZoneId = Objects.requireNonNull(zoneId, "시즌 시간대는 필수입니다");
        if (deadlineDayOffset == null) {
            deadlineAt = null;
            return;
        }
        deadlineAt = validatedMeetingDate
                .plusDays(deadlineDayOffset)
                .atTime(deadlineTime)
                .atZone(validatedZoneId)
                .toInstant();
    }

    public void updateCompletion(boolean completed) {
        status = completed ? RoutineStatus.DONE : RoutineStatus.WAITING;
    }

    public RoutineTimingStatus timingStatus(Clock clock, ZoneId zoneId) {
        Clock validatedClock = Objects.requireNonNull(clock, "현재 시각 기준은 필수입니다");
        ZoneId validatedZoneId = Objects.requireNonNull(zoneId, "시즌 시간대는 필수입니다");
        if (status == RoutineStatus.DONE) {
            return RoutineTimingStatus.COMPLETED;
        }
        if (deadlineAt == null) {
            return RoutineTimingStatus.UNSCHEDULED;
        }

        Instant now = validatedClock.instant();
        LocalDate today = now.atZone(validatedZoneId).toLocalDate();
        LocalDate deadlineDate = deadlineAt.atZone(validatedZoneId).toLocalDate();
        if (today.isBefore(deadlineDate)) {
            return RoutineTimingStatus.PLANNED;
        }
        if (now.isBefore(deadlineAt)) {
            return RoutineTimingStatus.IN_PROGRESS;
        }
        return RoutineTimingStatus.OVERDUE;
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

    public Integer getDeadlineDayOffset() {
        return deadlineDayOffset;
    }

    public LocalTime getDeadlineTime() {
        return deadlineTime;
    }

    public Instant getDeadlineAt() {
        return deadlineAt;
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
