package com.personal.baton.application.watch;

import com.personal.baton.application.watch.error.WatchHealthEventChangedAtOutOfRangeException;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public record WatchHealthChangedEvent(
        UUID eventId,
        String eventType,
        UUID resourceId,
        String resourceReference,
        long sourceRevision,
        UUID attemptId,
        WatchResourceHealth previousHealth,
        WatchResourceHealth currentHealth,
        Instant changedAt
) {

    public static final String RESOURCE_HEALTH_CHANGED = "RESOURCE_HEALTH_CHANGED";

    private static final Instant MIN_CHANGED_AT = Instant.parse("1000-01-01T00:00:00Z");
    private static final Instant MAX_CHANGED_AT_EXCLUSIVE =
            Instant.parse("+10000-01-01T00:00:00Z");
    private static final Pattern RESOURCE_REFERENCE_PATTERN = Pattern.compile(
            "[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"
    );

    public WatchHealthChangedEvent {
        Objects.requireNonNull(eventId, "WATCH eventId는 필수입니다");
        Objects.requireNonNull(eventType, "WATCH eventType은 필수입니다");
        Objects.requireNonNull(resourceId, "WATCH resourceId는 필수입니다");
        Objects.requireNonNull(resourceReference, "WATCH resourceReference는 필수입니다");
        Objects.requireNonNull(previousHealth, "WATCH previousHealth는 필수입니다");
        Objects.requireNonNull(currentHealth, "WATCH currentHealth는 필수입니다");
        Objects.requireNonNull(changedAt, "WATCH changedAt은 필수입니다");
        if (!RESOURCE_HEALTH_CHANGED.equals(eventType)) {
            throw new IllegalArgumentException("지원하지 않는 WATCH eventType입니다");
        }
        if (!RESOURCE_REFERENCE_PATTERN.matcher(resourceReference).matches()) {
            throw new IllegalArgumentException("WATCH resourceReference 형식이 올바르지 않습니다");
        }
        if (sourceRevision < 0) {
            throw new IllegalArgumentException("WATCH sourceRevision은 0 이상이어야 합니다");
        }
        if (previousHealth == currentHealth) {
            throw new IllegalArgumentException("WATCH health 변경 전후 값은 달라야 합니다");
        }
        if (changedAt.isBefore(MIN_CHANGED_AT)
                || !changedAt.isBefore(MAX_CHANGED_AT_EXCLUSIVE)) {
            throw new WatchHealthEventChangedAtOutOfRangeException();
        }
    }
}
