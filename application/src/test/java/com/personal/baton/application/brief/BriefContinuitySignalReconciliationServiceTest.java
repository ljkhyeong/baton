package com.personal.baton.application.brief;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.personal.baton.application.brief.port.in.ReconcileBriefContinuitySignalsUseCase.ReconciliationResult;
import com.personal.baton.application.brief.port.out.BriefContinuitySignalStorePort;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("usecase")
class BriefContinuitySignalReconciliationServiceTest {

    @DisplayName("한 시즌의 재조정 실패가 다음 시즌 처리를 막지 않는다")
    @Test
    void continuesAfterSeasonFailure() {
        BriefContinuitySignalStorePort storePort = mock(BriefContinuitySignalStorePort.class);
        BriefContinuitySignalReconciliationWorker worker = mock(
                BriefContinuitySignalReconciliationWorker.class
        );
        BriefContinuitySignalScope failed = new BriefContinuitySignalScope(
                UUID.randomUUID(),
                UUID.randomUUID()
        );
        BriefContinuitySignalScope succeeded = new BriefContinuitySignalScope(
                UUID.randomUUID(),
                UUID.randomUUID()
        );
        when(storePort.findReconciliationScopes()).thenReturn(List.of(failed, succeeded));
        when(worker.reconcile(failed.teamId(), failed.seasonId()))
                .thenThrow(new IllegalStateException("재조정 실패"));
        when(worker.reconcile(succeeded.teamId(), succeeded.seasonId())).thenReturn(2);
        BriefContinuitySignalReconciliationService service =
                new BriefContinuitySignalReconciliationService(storePort, worker);

        ReconciliationResult result = service.reconcileAll();

        assertThat(result.candidateCount()).isEqualTo(2);
        assertThat(result.appendedCount()).isEqualTo(2);
        assertThat(result.failedSeasonIds()).containsExactly(failed.seasonId());
    }
}
