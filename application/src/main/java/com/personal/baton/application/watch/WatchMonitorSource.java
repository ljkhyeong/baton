package com.personal.baton.application.watch;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

public record WatchMonitorSource(
        String namespace,
        boolean enabled,
        boolean monitoringEnabled
) {

    private static final Pattern NAMESPACE_PATTERN = Pattern.compile("[A-Za-z0-9._-]{1,63}");

    public WatchMonitorSource {
        Objects.requireNonNull(namespace, "WATCH source namespace는 필수입니다");
        if (!NAMESPACE_PATTERN.matcher(namespace).matches()) {
            throw new IllegalArgumentException(
                    "WATCH source namespace는 1~63자의 영문자, 숫자, 점, 밑줄, 하이픈이어야 합니다"
            );
        }
    }

    public WatchMonitorSource(String namespace) {
        this(namespace, true, true);
    }

    public String resourceReference(UUID resourceId) {
        Objects.requireNonNull(resourceId, "WATCH resourceId는 필수입니다");
        return resourceReferencePrefix() + "role-resource:" + resourceId;
    }

    public String resourceReferencePrefix() {
        return "baton-manager:" + namespace + ":";
    }

    public Optional<UUID> resourceId(String resourceReference) {
        if (resourceReference == null) {
            return Optional.empty();
        }
        String roleResourcePrefix = resourceReferencePrefix() + "role-resource:";
        if (!resourceReference.startsWith(roleResourcePrefix)) {
            return Optional.empty();
        }
        try {
            UUID resourceId = UUID.fromString(resourceReference.substring(roleResourcePrefix.length()));
            return resourceReference(resourceId).equals(resourceReference)
                    ? Optional.of(resourceId)
                    : Optional.empty();
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }
}
