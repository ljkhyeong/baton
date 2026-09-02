package com.personal.baton.adapter.out.external.calendar;

import static org.assertj.core.api.Assertions.assertThat;

import com.personal.baton.application.calendar.CalendarRecoveryManifest;
import com.personal.baton.application.calendar.CalendarSeasonMetadata;
import com.personal.baton.application.calendar.CalendarSnapshot;
import com.personal.baton.application.calendar.port.out.CalendarRecoveryStatePort.RecoveryState;
import com.personal.baton.application.calendar.port.out.CalendarRecoveryStatePort.SeasonState;
import com.personal.baton.application.calendar.port.out.CalendarSnapshotClient.DeliveryResult;
import com.personal.baton.application.calendar.port.out.CalendarSnapshotClient.Outcome;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

@Tag("calendar-recovery-crossservice")
class CalendarRecoveryConsumerContractTest {

    @Test
    @DisplayName("실제 CAL은 BATON의 시즌별 복구 상태를 검증한 뒤 전체 복구를 완료한다")
    void verifiesRecoveryManifestAgainstLiveCalendar() {
        RestClientCalendarClient client = client();
        UUID recoveryId = UUID.fromString("90000000-0000-0000-0000-000000000001");
        UUID seasonId = UUID.fromString("92000000-0000-0000-0000-000000000001");
        Instant sourceUpdatedAt = Instant.parse("2026-09-01T03:00:00Z");
        CalendarSnapshot snapshot = new CalendarSnapshot(
                UUID.fromString("91000000-0000-0000-0000-000000000001"),
                sourceUpdatedAt.plusSeconds(1),
                UUID.fromString("93000000-0000-0000-0000-000000000001"),
                seasonId,
                3,
                CalendarSnapshot.Status.ACTIVE,
                "복구 검증 일정",
                "복구 상태 해시를 실제 CAL 데이터와 대조합니다",
                null,
                new CalendarSnapshot.AllDay(
                        LocalDate.of(2026, 9, 1),
                        LocalDate.of(2026, 9, 2)
                ),
                sourceUpdatedAt
        );
        CalendarSeasonMetadata metadata = new CalendarSeasonMetadata(seasonId, 2, "복구 검증 시즌");

        assertDelivered(client.deliver(snapshot), "APPLIED");
        assertDelivered(client.deliver(metadata), "SEASON_METADATA_ACCEPTED");

        CalendarRecoveryManifest manifest = CalendarRecoveryManifest.from(new RecoveryState(List.of(
                new SeasonState(seasonId, List.of(snapshot), metadata)
        )));
        assertDelivered(client.verifySeason(recoveryId, manifest.seasons().getFirst()),
                "RECOVERY_SEASON_VERIFIED");
        assertDelivered(client.complete(recoveryId, manifest), "RECOVERY_COMPLETED");
        assertDelivered(client.complete(recoveryId, manifest), "RECOVERY_COMPLETED");
    }

    private RestClientCalendarClient client() {
        String baseUrl = System.getenv("BATON_CAL_LIVE_BASE_URL");
        String bearerToken = System.getenv("BATON_CAL_LIVE_BEARER_TOKEN");
        return new RestClientCalendarClient(RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                .build());
    }

    private void assertDelivered(DeliveryResult result, String code) {
        assertThat(result.outcome()).isEqualTo(Outcome.DELIVERED);
        assertThat(result.code()).isEqualTo(code);
    }
}
