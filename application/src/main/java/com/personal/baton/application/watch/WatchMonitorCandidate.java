package com.personal.baton.application.watch;

import java.util.Objects;
import java.util.UUID;

public record WatchMonitorCandidate(UUID resourceId, String targetUrl, boolean seasonEnded) {

    public WatchMonitorCandidate {
        Objects.requireNonNull(resourceId, "WATCH reconciliation resourceId는 필수입니다");
        Objects.requireNonNull(targetUrl, "WATCH reconciliation targetUrl은 필수입니다");
    }
}
