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
        if (source.enabled()
                && source.monitoringEnabled()
                && resource.getArchivedAt() == null
                && eligibilityPolicy.isEligible(resource.getUrl())) {
            appendActive(resource.getId(), resource.getUrl());
        }
    }

    public void recordUpdated(String previousUrl, RoleResource resource) {
        Objects.requireNonNull(resource, "역할 자료는 필수입니다");
        if (!source.enabled()) {
            return;
        }
        boolean previouslyEligible = eligibilityPolicy.isEligible(previousUrl);
        boolean currentlyEligible = source.monitoringEnabled()
                && resource.getArchivedAt() == null
                && eligibilityPolicy.isEligible(resource.getUrl());
        if (currentlyEligible) {
            appendActive(resource.getId(), resource.getUrl());
        } else if (previouslyEligible) {
            appendInactive(resource.getId());
        }
    }

    public int recordSeasonState(List<RoleResource> resources, boolean ended) {
        Objects.requireNonNull(resources, "시즌 역할 자료 목록은 필수입니다");
        if (!source.enabled()) {
            return 0;
        }
        int appendedCount = 0;
        for (RoleResource resource : resources) {
            boolean appended = ended
                    || resource.getArchivedAt() != null
                    || !source.monitoringEnabled()
                    || !eligibilityPolicy.isEligible(resource.getUrl())
                    ? appendInactive(resource.getId())
                    : appendActive(resource.getId(), resource.getUrl());
            if (appended) {
                appendedCount++;
            }
        }
        return appendedCount;
    }

    public boolean reconcile(WatchMonitorCandidate candidate) {
        Objects.requireNonNull(candidate, "WATCH reconciliation candidate는 필수입니다");
        if (!source.enabled()) {
            return false;
        }
        WatchMonitorChange change = !source.monitoringEnabled()
                || candidate.inactive()
                || !eligibilityPolicy.isEligible(candidate.targetUrl())
                ? inactiveChange(candidate.resourceId())
                : activeChange(candidate.resourceId(), candidate.targetUrl());
        return outboxPort.appendReconciledIfCurrent(candidate, change);
    }

    private boolean appendActive(UUID resourceId, String targetUrl) {
        return outboxPort.appendIfChanged(activeChange(resourceId, targetUrl));
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

    private boolean appendInactive(UUID resourceId) {
        return outboxPort.appendIfChanged(inactiveChange(resourceId));
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
