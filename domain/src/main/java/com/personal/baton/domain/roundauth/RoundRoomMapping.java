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
        name = "round_room_mappings",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uk_round_room_mappings_room",
                    columnNames = "room_id"
            ),
            @UniqueConstraint(
                    name = "uk_round_room_mappings_resource",
                    columnNames = "resource_id"
            )
        }
)
public class RoundRoomMapping {

    @Id
    @Column(nullable = false, columnDefinition = "binary(16)")
    private UUID id;

    @Column(name = "room_id", nullable = false, length = 14)
    private String roomId;

    @Column(name = "team_id", nullable = false, columnDefinition = "binary(16)")
    private UUID teamId;

    @Column(name = "season_id", nullable = false, columnDefinition = "binary(16)")
    private UUID seasonId;

    @Column(name = "resource_id", nullable = false, columnDefinition = "binary(16)")
    private UUID resourceId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected RoundRoomMapping() {
    }

    private RoundRoomMapping(
            UUID id,
            String roomId,
            UUID teamId,
            UUID seasonId,
            UUID resourceId,
            Instant createdAt
    ) {
        this.id = Objects.requireNonNull(id, "ROUND 방 매핑 식별자는 필수입니다");
        this.roomId = new RoundRoomId(roomId).value();
        this.teamId = Objects.requireNonNull(teamId, "팀 식별자는 필수입니다");
        this.seasonId = Objects.requireNonNull(seasonId, "시즌 식별자는 필수입니다");
        this.resourceId = Objects.requireNonNull(resourceId, "자료 식별자는 필수입니다");
        this.createdAt = Objects.requireNonNull(createdAt, "ROUND 방 매핑 생성 시각은 필수입니다");
    }

    public static RoundRoomMapping create(
            UUID id,
            String roomId,
            UUID teamId,
            UUID seasonId,
            UUID resourceId,
            Instant createdAt
    ) {
        return new RoundRoomMapping(id, roomId, teamId, seasonId, resourceId, createdAt);
    }

    public String getRoomId() {
        return roomId;
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

    public Instant getCreatedAt() {
        return createdAt;
    }
}
