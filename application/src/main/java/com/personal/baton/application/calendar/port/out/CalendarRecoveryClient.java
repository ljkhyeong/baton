package com.personal.baton.application.calendar.port.out;

import com.personal.baton.application.calendar.CalendarRecoveryManifest;
import com.personal.baton.application.calendar.port.out.CalendarSnapshotClient.DeliveryResult;
import java.util.UUID;
import java.util.Optional;
import java.time.Instant;

public interface CalendarRecoveryClient {

    enum RunStatus { IN_PROGRESS, COMPLETED }
    record RecoveryRun(UUID recoveryId, RunStatus status, boolean recoveryMode, int verifiedSeasonCount, Instant completedAt) {}
    record SeasonState(UUID seasonId, int itemCount, String itemDigest, Integer metadataRevision, String metadataDigest) {}

    // 진단 조회가 불가능하면 기존 전달 결과를 유지하며 완료 판단을 대신하지 않는다.
    Optional<RecoveryRun> findRecoveryRun(UUID recoveryId);
    Optional<SeasonState> findRecoverySeason(UUID seasonId);

    DeliveryResult verifySeason(UUID recoveryId, CalendarRecoveryManifest.Season season);

    DeliveryResult complete(UUID recoveryId, CalendarRecoveryManifest manifest);
}
