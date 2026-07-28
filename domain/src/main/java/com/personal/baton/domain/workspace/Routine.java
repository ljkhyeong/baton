package com.personal.baton.domain.workspace;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
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

    @Column(nullable = false, length = 1000)
    private String detail;

    @Version
    @Column(nullable = false)
    private Long version;

    protected Routine() {
    }

    private Routine(
            UUID id,
            UUID seasonId,
            String title,
            RoutinePhase phase,
            String dueLabel,
            UUID ownerRoleId,
            String detail
    ) {
        this.id = Objects.requireNonNull(id, "루틴 식별자는 필수입니다");
        this.seasonId = Objects.requireNonNull(seasonId, "시즌 식별자는 필수입니다");
        update(title, phase, dueLabel, ownerRoleId, detail);
    }

    public static Routine create(
            UUID id,
            UUID seasonId,
            String title,
            RoutinePhase phase,
            String dueLabel,
            UUID ownerRoleId,
            String detail
    ) {
        return new Routine(id, seasonId, title, phase, dueLabel, ownerRoleId, detail);
    }

    public void update(
            String title,
            RoutinePhase phase,
            String dueLabel,
            UUID ownerRoleId,
            String detail
    ) {
        String normalizedTitle = DomainAssertions.requiredText(title, "루틴 제목", 200);
        RoutinePhase validatedPhase = Objects.requireNonNull(phase, "루틴 단계는 필수입니다");
        String normalizedDueLabel = DomainAssertions.requiredText(dueLabel, "루틴 기한 문구", 100);
        UUID validatedOwnerRoleId = Objects.requireNonNull(ownerRoleId, "담당 역할은 필수입니다");
        String normalizedDetail = DomainAssertions.requiredText(detail, "루틴 상세", 1000);

        this.title = normalizedTitle;
        this.phase = validatedPhase;
        this.dueLabel = normalizedDueLabel;
        this.ownerRoleId = validatedOwnerRoleId;
        this.detail = normalizedDetail;
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

    public String getDetail() {
        return detail;
    }
}
