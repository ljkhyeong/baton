package com.personal.baton.adapter.out.external.calendar;

import static org.assertj.core.api.Assertions.assertThat;

import com.personal.baton.application.calendar.CalendarSnapshot;
import com.personal.baton.application.calendar.port.out.CalendarSnapshotClient.DeliveryResult;
import com.personal.baton.application.calendar.port.out.CalendarSnapshotClient.Outcome;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

@Tag("calendar-crossservice")
class CalendarConsumerContractTest {

    private static final UUID SOURCE_ITEM_ID = UUID.fromString(
            "10000000-0000-0000-0000-000000000001"
    );
    private static final UUID SEASON_ID = UUID.fromString(
            "20000000-0000-0000-0000-000000000001"
    );
    private static final Instant BASE_TIME = Instant.parse("2026-08-27T03:00:00Z");

    @DisplayName("실제 CAL은 BATON 생성·변경·취소와 중복·역순 전달을 계약대로 판정한다")
    @Test
    void deliversBatonSnapshotsToLiveCalendar() {
        RestClientCalendarClient client = client();
        CalendarSnapshot created = snapshot(
                "30000000-0000-0000-0000-000000000001",
                0,
                CalendarSnapshot.Status.ACTIVE,
                "첫 일정",
                LocalDate.of(2026, 8, 27),
                BASE_TIME
        );
        CalendarSnapshot changed = snapshot(
                "30000000-0000-0000-0000-000000000003",
                2,
                CalendarSnapshot.Status.ACTIVE,
                "변경된 일정",
                LocalDate.of(2026, 8, 29),
                BASE_TIME.plusSeconds(2)
        );
        CalendarSnapshot outOfOrder = snapshot(
                "30000000-0000-0000-0000-000000000002",
                1,
                CalendarSnapshot.Status.ACTIVE,
                "늦게 도착한 변경",
                LocalDate.of(2026, 8, 28),
                BASE_TIME.plusSeconds(1)
        );
        CalendarSnapshot cancelled = snapshot(
                "30000000-0000-0000-0000-000000000004",
                3,
                CalendarSnapshot.Status.CANCELLED,
                "변경된 일정",
                LocalDate.of(2026, 8, 29),
                BASE_TIME.plusSeconds(3)
        );

        assertDelivered(client.deliver(created), "APPLIED");
        assertDelivered(client.deliver(changed), "APPLIED");
        assertDelivered(client.deliver(changed), "DUPLICATE");
        assertDelivered(client.deliver(outOfOrder), "STALE");
        assertDelivered(client.deliver(cancelled), "APPLIED");
    }

    private RestClientCalendarClient client() {
        String baseUrl = System.getenv("BATON_CAL_LIVE_BASE_URL");
        String bearerToken = System.getenv("BATON_CAL_LIVE_BEARER_TOKEN");
        RestClient restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                .build();
        return new RestClientCalendarClient(restClient);
    }

    private CalendarSnapshot snapshot(
            String eventId,
            int revision,
            CalendarSnapshot.Status status,
            String summary,
            LocalDate startDate,
            Instant sourceUpdatedAt
    ) {
        return new CalendarSnapshot(
                UUID.fromString(eventId),
                sourceUpdatedAt.plusSeconds(10),
                SOURCE_ITEM_ID,
                SEASON_ID,
                revision,
                status,
                summary,
                null,
                null,
                new CalendarSnapshot.AllDay(startDate, startDate.plusDays(1)),
                sourceUpdatedAt
        );
    }

    private void assertDelivered(DeliveryResult result, String code) {
        assertThat(result.outcome()).isEqualTo(Outcome.DELIVERED);
        assertThat(result.code()).isEqualTo(code);
    }
}
