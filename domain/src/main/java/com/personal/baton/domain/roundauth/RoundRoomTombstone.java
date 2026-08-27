package com.personal.baton.domain.roundauth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(
        name = "round_room_tombstones",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_round_room_tombstones_snapshot",
                columnNames = {"room_id", "team_id", "season_id", "resource_id"}
        )
)
public class RoundRoomTombstone {

    @Id
    @Column(name = "room_id", nullable = false, length = 14)
    private String roomId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "team_id", nullable = false, columnDefinition = "binary(16)")
    private UUID teamId;

    @Column(name = "season_id", nullable = false, columnDefinition = "binary(16)")
    private UUID seasonId;

    @Column(name = "resource_id", nullable = false, columnDefinition = "binary(16)")
    private UUID resourceId;

    @Column(name = "ended_at")
    private Instant endedAt;

    protected RoundRoomTombstone() {
    }

    private RoundRoomTombstone(
            String roomId,
            UUID teamId,
            UUID seasonId,
            UUID resourceId,
            Instant createdAt
    ) {
        this.roomId = new RoundRoomId(roomId).value();
        this.teamId = Objects.requireNonNull(teamId, "팀 식별자는 필수입니다");
        this.seasonId = Objects.requireNonNull(seasonId, "시즌 식별자는 필수입니다");
        this.resourceId = Objects.requireNonNull(resourceId, "자료 식별자는 필수입니다");
        this.createdAt = Objects.requireNonNull(createdAt, "ROUND 방 생성 시각은 필수입니다");
    }

    public static RoundRoomTombstone create(
            String roomId,
            UUID teamId,
            UUID seasonId,
            UUID resourceId,
            Instant createdAt
    ) {
        return new RoundRoomTombstone(roomId, teamId, seasonId, resourceId, createdAt);
    }

    public void end(Instant endedAt) {
        Instant requiredEndedAt = Objects.requireNonNull(
                endedAt,
                "ROUND 방 종료 시각은 필수입니다"
        );
        if (requiredEndedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("ROUND 방 종료 시각은 생성 시각보다 빠를 수 없습니다");
        }
        if (this.endedAt == null) {
            this.endedAt = requiredEndedAt;
        }
    }

    public String getRoomId() {
        return roomId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public UUID getTeamId() {
        return teamId;
    }

    public UUID getSeasonId() {
        return seasonId;
    }

    public UUID getResourceId() {
        return resourceId;
    }

    public Instant getEndedAt() {
        return endedAt;
    }

    public boolean isEnded() {
        return endedAt != null;
    }
}
