package com.personal.baton.application.calendar;

import com.personal.baton.application.calendar.port.in.CompleteCalendarRecoveryUseCase;
import com.personal.baton.application.calendar.port.out.CalendarRecoveryClient;
import com.personal.baton.application.calendar.port.out.CalendarRecoveryStatePort;
import com.personal.baton.application.calendar.port.out.CalendarSnapshotClient.DeliveryResult;
import java.util.UUID;
import java.util.Objects;
import com.personal.baton.application.calendar.port.out.CalendarRecoveryClient.RunStatus;
import com.personal.baton.application.calendar.port.out.CalendarSnapshotClient.Outcome;
import org.springframework.stereotype.Service;

@Service
public class CalendarRecoveryCompletionService implements CompleteCalendarRecoveryUseCase {

    private final CalendarRecoveryStatePort statePort;
    private final CalendarRecoveryClient client;

    public CalendarRecoveryCompletionService(
            CalendarRecoveryStatePort statePort,
            CalendarRecoveryClient client
    ) {
        this.statePort = statePort;
        this.client = client;
    }

    @Override
    public Result complete(UUID recoveryId) {
        var state = statePort.loadReadyState();
        if (state.isEmpty()) {
            return Result.waiting("CAL_OUTBOX_NOT_READY");
        }
        CalendarRecoveryManifest manifest = CalendarRecoveryManifest.from(state.get());
        for (var season : manifest.seasons()) {
            var delivery = client.verifySeason(recoveryId, season);
            Result failure = failure(delivery);
            if ("RECOVERY_MANIFEST_MISMATCH".equals(delivery.code())) {
                failure = client.findRecoverySeason(season.seasonId())
                        .map(actual -> Result.waiting(mismatchCode(season, actual)))
                        .orElse(failure);
            }
            if (failure != null) {
                return failure;
            }
        }
        var completion = client.complete(recoveryId, manifest);
        Result failure = failure(completion);
        if (completion.outcome() == Outcome.RETRYABLE_FAILURE
                && client.findRecoveryRun(recoveryId).filter(run -> run.status() == RunStatus.COMPLETED).isPresent()) {
            // 과거 완료를 확인했어도 다음 주기에 현재 원본 매니페스트를 다시 검증한다.
            return Result.waiting("CAL_RECOVERY_COMPLETION_RECHECK_REQUIRED");
        }
        return failure == null ? Result.completed(manifest.seasons().size()) : failure;
    }

    private String mismatchCode(CalendarRecoveryManifest.Season expected, CalendarRecoveryClient.SeasonState actual) {
        if (actual.itemCount() != expected.itemCount() || !actual.itemDigest().equals(expected.itemDigest())) {
            return "CAL_RECOVERY_ITEMS_MISMATCH";
        }
        if (!Objects.equals(actual.metadataRevision(), expected.metadataRevision())
                || !Objects.equals(actual.metadataDigest(), expected.metadataDigest())) {
            return "CAL_RECOVERY_METADATA_MISMATCH";
        }
        return "RECOVERY_MANIFEST_MISMATCH";
    }

    private Result failure(DeliveryResult delivery) {
        return switch (delivery.outcome()) {
            case DELIVERED -> null;
            case RETRYABLE_FAILURE -> Result.waiting(delivery.code());
            case PERMANENT_FAILURE -> Result.failed(delivery.code());
        };
    }
}
