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
import java.time.LocalTime;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(
        name = "routines",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_routines_season_previous_routine",
                columnNames = {"season_id", "previous_routine_id"}
        )
)
public class Routine {

    @Id
    @Column(nullable = false, columnDefinition = "binary(16)")
    private UUID id;

    @Column(name = "season_id", nullable = false, columnDefinition = "binary(16)")
    private UUID seasonId;

    @Column(name = "previous_routine_id", columnDefinition = "binary(16)")
    private UUID previousRoutineId;

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

    @Column(name = "owner_role_id", nullable = false, columnDefinition = "binary(16)")
    private UUID ownerRoleId;

    @Column(nullable = false, length = 1000)
    private String detail;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @Version
    @Column(nullable = false)
    private Long version;

    protected Routine() {
    }

    private Routine(
            UUID id,
            UUID seasonId,
            UUID previousRoutineId,
            String title,
            RoutinePhase phase,
            String dueLabel,
            UUID ownerRoleId,
            String detail,
            Integer deadlineDayOffset,
            LocalTime deadlineTime
    ) {
        this.id = Objects.requireNonNull(id, "루틴 식별자는 필수입니다");
        this.seasonId = Objects.requireNonNull(seasonId, "시즌 식별자는 필수입니다");
        this.previousRoutineId = previousRoutineId;
        update(title, phase, dueLabel, ownerRoleId, detail, deadlineDayOffset, deadlineTime);
    }

    public static Routine create(
            UUID id,
            UUID seasonId,
            String title,
            RoutinePhase phase,
            String dueLabel,
            UUID ownerRoleId,
            String detail,
            Integer deadlineDayOffset,
            LocalTime deadlineTime
    ) {
        return new Routine(
                id,
                seasonId,
                null,
                title,
                phase,
                dueLabel,
                ownerRoleId,
                detail,
                deadlineDayOffset,
                deadlineTime
        );
    }

    public Routine copyToSeason(UUID id, UUID targetSeasonId, UUID targetOwnerRoleId) {
        requireActive();
        return new Routine(
                id,
                targetSeasonId,
                this.id,
                title,
                phase,
                dueLabel,
                targetOwnerRoleId,
                detail,
                deadlineDayOffset,
                deadlineTime
        );
    }

    public void update(
            String title,
            RoutinePhase phase,
            String dueLabel,
            UUID ownerRoleId,
            String detail,
            Integer deadlineDayOffset,
            LocalTime deadlineTime
    ) {
        requireActive();
        String normalizedTitle = DomainAssertions.requiredText(title, "루틴 제목", 200);
        RoutinePhase validatedPhase = Objects.requireNonNull(phase, "루틴 단계는 필수입니다");
        String normalizedDueLabel = DomainAssertions.requiredText(dueLabel, "루틴 기한 문구", 100);
        UUID validatedOwnerRoleId = Objects.requireNonNull(ownerRoleId, "담당 역할은 필수입니다");
        String normalizedDetail = DomainAssertions.requiredText(detail, "루틴 상세", 1000);
        validateDeadlineRule(deadlineDayOffset, deadlineTime);

        this.title = normalizedTitle;
        this.phase = validatedPhase;
        this.dueLabel = normalizedDueLabel;
        this.ownerRoleId = validatedOwnerRoleId;
        this.detail = normalizedDetail;
        this.deadlineDayOffset = deadlineDayOffset;
        this.deadlineTime = deadlineTime;
    }

    public void updateArchive(boolean archived, Instant archivedAt) {
        if (archived) {
            if (this.archivedAt == null) {
                this.archivedAt = Objects.requireNonNull(archivedAt, "루틴 보관 시각은 필수입니다");
            }
            return;
        }
        this.archivedAt = null;
    }

    private void requireActive() {
        if (archivedAt != null) {
            throw new DomainValidationException("보관된 루틴은 수정하거나 복사할 수 없습니다");
        }
    }

    private static void validateDeadlineRule(Integer deadlineDayOffset, LocalTime deadlineTime) {
        if ((deadlineDayOffset == null) != (deadlineTime == null)) {
            throw new DomainValidationException("마감 날짜 오프셋과 마감 시각은 함께 설정해야 합니다");
        }
        if (deadlineDayOffset != null && (deadlineDayOffset < -30 || deadlineDayOffset > 30)) {
            throw new DomainValidationException("마감 날짜 오프셋은 -30일 이상 30일 이하여야 합니다");
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getSeasonId() {
        return seasonId;
    }

    public UUID getPreviousRoutineId() {
        return previousRoutineId;
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

    public UUID getOwnerRoleId() {
        return ownerRoleId;
    }

    public String getDetail() {
        return detail;
    }

    public Instant getArchivedAt() {
        return archivedAt;
    }
}
