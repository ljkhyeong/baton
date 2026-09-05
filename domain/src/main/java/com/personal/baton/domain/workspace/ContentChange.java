package com.personal.baton.domain.workspace;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "content_changes")
public class ContentChange {
    @Id @Column(columnDefinition = "binary(16)")
    private UUID id;
    @Column(name = "team_id", nullable = false, columnDefinition = "binary(16)")
    private UUID teamId;
    @Column(name = "season_id", nullable = false, columnDefinition = "binary(16)")
    private UUID seasonId;
    @Enumerated(EnumType.STRING) @Column(name = "record_kind", nullable = false, length = 20)
    private ContentRecordKind recordKind;
    @Column(name = "record_id", nullable = false, columnDefinition = "binary(16)")
    private UUID recordId;
    @Column(name = "actor_account_id", columnDefinition = "binary(16)")
    private UUID actorAccountId;
    @Column(name = "actor_name", nullable = false, length = 100)
    private String actorName;
    @Column(name = "changed_at", nullable = false)
    private Instant changedAt;
    @ElementCollection
    @CollectionTable(name = "content_change_fields", joinColumns = @JoinColumn(name = "change_id"))
    @OrderColumn(name = "field_position")
    private List<ContentFieldChange> fields = new ArrayList<>();

    protected ContentChange() {}
    public static ContentChange create(UUID teamId, UUID seasonId, ContentRecordKind kind, UUID recordId,
            UUID actorAccountId, String actorName, Instant changedAt, List<ContentFieldChange> fields) {
        if (fields.isEmpty()) throw new DomainValidationException("수정된 항목이 없습니다");
        ContentChange change = new ContentChange();
        change.id = UUID.randomUUID(); change.teamId = teamId; change.seasonId = seasonId;
        change.recordKind = kind; change.recordId = recordId; change.actorAccountId = actorAccountId;
        change.actorName = DomainAssertions.requiredText(actorName, "변경자 이름", 100);
        change.changedAt = changedAt; change.fields.addAll(fields);
        return change;
    }
    public UUID getId() { return id; }
    public UUID getTeamId() { return teamId; }
    public UUID getSeasonId() { return seasonId; }
    public ContentRecordKind getRecordKind() { return recordKind; }
    public UUID getRecordId() { return recordId; }
    public UUID getActorAccountId() { return actorAccountId; }
    public String getActorName() { return actorName; }
    public Instant getChangedAt() { return changedAt; }
    public List<ContentFieldChange> getFields() { return List.copyOf(fields); }
}
