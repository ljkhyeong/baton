package com.personal.baton.application.watch;

import com.personal.baton.application.watch.port.out.WatchMonitorOutboxPort;
import com.personal.baton.domain.workspace.RoleResource;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class WatchMonitorChangeRecorder {

    private final WatchMonitorOutboxPort outboxPort;
    private final WatchMonitorEligibilityPolicy eligibilityPolicy;
    private final WatchMonitorSource source;
    private final Clock clock;

    public WatchMonitorChangeRecorder(
            WatchMonitorOutboxPort outboxPort,
            WatchMonitorSource source,
            Clock clock
    ) {
        this.outboxPort = outboxPort;
        this.eligibilityPolicy = new WatchMonitorEligibilityPolicy();
        this.source = source;
        this.clock = clock;
    }

    public void recordCreated(RoleResource resource) {
        Objects.requireNonNull(resource, "역할 자료는 필수입니다");
        if (source.enabled() && shouldMonitor(resource.isArchived(), resource.getUrl())) {
            appendActive(resource.getId(), resource.getUrl());
        }
    }

    public void recordUpdated(String previousUrl, RoleResource resource) {
        Objects.requireNonNull(resource, "역할 자료는 필수입니다");
        if (!source.enabled()) {
            return;
        }
        boolean previouslyEligible = eligibilityPolicy.isEligible(previousUrl);
        if (shouldMonitor(resource.isArchived(), resource.getUrl())) {
            appendActive(resource.getId(), resource.getUrl());
        } else if (previouslyEligible) {
            appendInactive(resource.getId());
        }
    }

    public void recordSeasonState(List<RoleResource> resources, boolean ended) {
        Objects.requireNonNull(resources, "시즌 역할 자료 목록은 필수입니다");
        if (!source.enabled()) {
            return;
        }
        for (RoleResource resource : resources) {
            if (shouldMonitor(ended || resource.isArchived(), resource.getUrl())) {
                appendActive(resource.getId(), resource.getUrl());
            } else {
                appendInactive(resource.getId());
            }
        }
    }

    public boolean reconcile(WatchMonitorCandidate candidate) {
        Objects.requireNonNull(candidate, "WATCH reconciliation candidate는 필수입니다");
        if (!source.enabled()) {
            return false;
        }
        WatchMonitorChange change = shouldMonitor(candidate.inactive(), candidate.targetUrl())
                ? activeChange(candidate.resourceId(), candidate.targetUrl())
                : inactiveChange(candidate.resourceId());
        return outboxPort.appendReconciledIfCurrent(candidate, change);
    }

    // 점검이 켜져 있고 보관·종료되지 않은 자료의 적격 URL만 감시한다.
    private boolean shouldMonitor(boolean inactive, String targetUrl) {
        return source.monitoringEnabled() && !inactive && eligibilityPolicy.isEligible(targetUrl);
    }

    private void appendActive(UUID resourceId, String targetUrl) {
        outboxPort.appendIfChanged(activeChange(resourceId, targetUrl));
    }

    private WatchMonitorChange activeChange(UUID resourceId, String targetUrl) {
        return new WatchMonitorChange(
                UUID.randomUUID(),
                resourceId,
                source.resourceReference(resourceId),
                WatchMonitoringState.ACTIVE,
                targetUrl,
                Instant.now(clock)
        );
    }

    private void appendInactive(UUID resourceId) {
        outboxPort.appendIfChanged(inactiveChange(resourceId));
    }

    private WatchMonitorChange inactiveChange(UUID resourceId) {
        return new WatchMonitorChange(
                UUID.randomUUID(),
                resourceId,
                source.resourceReference(resourceId),
                WatchMonitoringState.INACTIVE,
                null,
                Instant.now(clock)
        );
    }
}
