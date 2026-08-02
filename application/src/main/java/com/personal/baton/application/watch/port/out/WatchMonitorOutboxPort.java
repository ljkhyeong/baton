package com.personal.baton.application.watch.port.out;

import com.personal.baton.application.watch.WatchMonitorCandidate;
import com.personal.baton.application.watch.WatchMonitorChange;
import com.personal.baton.application.watch.WatchMonitorDelivery;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface WatchMonitorOutboxPort {

    boolean appendIfChanged(WatchMonitorChange change);

    boolean appendReconciledIfCurrent(
            WatchMonitorCandidate expectedCandidate,
            WatchMonitorChange change
    );

    List<WatchMonitorDelivery> claimPending(
            int batchSize,
            Instant claimedAt,
            Duration leaseDuration
    );

    boolean markDelivered(
            long sourceRevision,
            UUID leaseToken,
            Instant deliveredAt,
            String resultCode
    );

    boolean markRetry(
            long sourceRevision,
            UUID leaseToken,
            Instant availableAt,
            String errorCode
    );

    boolean markFailed(
            long sourceRevision,
            UUID leaseToken,
            Instant failedAt,
            String errorCode
    );

    boolean markInvalidTargetAndAppendInactive(
            long sourceRevision,
            UUID leaseToken,
            UUID compensationEventId,
            Instant failedAt
    );

    int requeueOperationalFailures(Instant availableAt);

    boolean hasMismatchedResourceReferencePrefix(String expectedPrefix);

    List<WatchMonitorCandidate> findReconciliationCandidates();
}
