package com.personal.baton.application.brief;

import com.personal.baton.application.workspace.port.in.ContinuitySignalSeverity;
import com.personal.baton.application.workspace.port.in.ContinuitySignalType;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record BriefContinuityEvent(
        UUID eventId,
        ContinuitySignalType eventType,
        int eventVersion,
        ContinuitySignalSeverity sourceSeverity,
        UUID workspaceId,
        UUID seasonId,
        String sourceReference,
        long aggregateRevision,
        Instant occurredAt,
        State state
) {

    private static final String SOURCE_REFERENCE_PREFIX = "baton-continuity:";

    public BriefContinuityEvent {
        Objects.requireNonNull(eventId, "eventId는 필수입니다");
        Objects.requireNonNull(eventType, "eventType은 필수입니다");
        Objects.requireNonNull(sourceSeverity, "sourceSeverity는 필수입니다");
        Objects.requireNonNull(workspaceId, "workspaceId는 필수입니다");
        Objects.requireNonNull(seasonId, "seasonId는 필수입니다");
        Objects.requireNonNull(sourceReference, "sourceReference는 필수입니다");
        Objects.requireNonNull(occurredAt, "occurredAt은 필수입니다");
        Objects.requireNonNull(state, "state는 필수입니다");
        if (eventVersion != 2) {
            throw new IllegalArgumentException("eventVersion은 2여야 합니다");
        }
        if (sourceReference.isBlank() || sourceReference.length() > 128) {
            throw new IllegalArgumentException("sourceReference는 공백이 아닌 128자 이하여야 합니다");
        }
        if (aggregateRevision < 1) {
            throw new IllegalArgumentException("aggregateRevision은 양수여야 합니다");
        }
    }

    public static String sourceReference(UUID signalId) {
        return SOURCE_REFERENCE_PREFIX + signalId;
    }

    // 정규 UUID 문자열로 만든 참조만 신호 ID로 해석한다.
    public static Optional<UUID> signalId(String sourceReference) {
        if (!sourceReference.startsWith(SOURCE_REFERENCE_PREFIX)) {
            return Optional.empty();
        }
        try {
            UUID signalId = UUID.fromString(sourceReference.substring(SOURCE_REFERENCE_PREFIX.length()));
            return sourceReference.equals(sourceReference(signalId)) ? Optional.of(signalId) : Optional.empty();
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    public enum State {
        ACTIVE,
        RESOLVED
    }
}
