package com.personal.baton.application.watch;

import com.personal.baton.application.watch.port.out.WatchMonitorOutboxPort;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("policy")
class WatchMonitorReconciliationServiceTest {

    @DisplayName("reconciliation은 고정 크기 UUID keyset page를 끝까지 순회한다")
    @Test
    void reconcilesCandidatesAcrossKeysetPages() {
        WatchMonitorOutboxPort outboxPort = mock(WatchMonitorOutboxPort.class);
        WatchMonitorChangeRecorder changeRecorder = mock(WatchMonitorChangeRecorder.class);
        List<WatchMonitorCandidate> firstPage = candidates(
                1,
                WatchMonitorReconciliationService.RECONCILIATION_PAGE_SIZE
        );
        List<WatchMonitorCandidate> secondPage = candidates(101, 2);
        UUID firstPageCursor = firstPage.getLast().resourceId();
        when(outboxPort.findReconciliationCandidates(
                null,
                WatchMonitorReconciliationService.RECONCILIATION_PAGE_SIZE
        )).thenReturn(firstPage);
        when(outboxPort.findReconciliationCandidates(
                firstPageCursor,
                WatchMonitorReconciliationService.RECONCILIATION_PAGE_SIZE
        )).thenReturn(secondPage);
        when(changeRecorder.reconcile(any())).thenReturn(true);
        WatchMonitorReconciliationService service = new WatchMonitorReconciliationService(
                outboxPort,
                changeRecorder
        );

        var result = service.reconcile();

        assertThat(result.candidateCount()).isEqualTo(102);
        assertThat(result.appendedCount()).isEqualTo(102);
        verify(outboxPort).findReconciliationCandidates(
                null,
                WatchMonitorReconciliationService.RECONCILIATION_PAGE_SIZE
        );
        verify(outboxPort).findReconciliationCandidates(
                firstPageCursor,
                WatchMonitorReconciliationService.RECONCILIATION_PAGE_SIZE
        );
    }

    @DisplayName("reconciliation 후보가 없으면 빈 결과로 끝난다")
    @Test
    void completesWithEmptyResultWhenNoCandidateExists() {
        WatchMonitorOutboxPort outboxPort = mock(WatchMonitorOutboxPort.class);
        WatchMonitorChangeRecorder changeRecorder = mock(WatchMonitorChangeRecorder.class);
        when(outboxPort.findReconciliationCandidates(
                null,
                WatchMonitorReconciliationService.RECONCILIATION_PAGE_SIZE
        )).thenReturn(List.of());
        WatchMonitorReconciliationService service = new WatchMonitorReconciliationService(
                outboxPort,
                changeRecorder
        );

        var result = service.reconcile();

        assertThat(result.candidateCount()).isZero();
        assertThat(result.appendedCount()).isZero();
    }

    private List<WatchMonitorCandidate> candidates(int firstIndex, int count) {
        List<WatchMonitorCandidate> candidates = new ArrayList<>(count);
        for (int index = firstIndex; index < firstIndex + count; index++) {
            candidates.add(new WatchMonitorCandidate(
                    new UUID(0, index),
                    "https://example.com/resource/" + index,
                    false
            ));
        }
        return List.copyOf(candidates);
    }
}
