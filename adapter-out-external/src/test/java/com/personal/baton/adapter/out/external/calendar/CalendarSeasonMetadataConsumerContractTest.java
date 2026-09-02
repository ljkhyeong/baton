package com.personal.baton.adapter.out.external.calendar;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.personal.baton.application.calendar.CalendarSeasonMetadata;
import com.personal.baton.application.calendar.CalendarSnapshot;
import com.personal.baton.application.calendar.port.out.CalendarSnapshotClient.DeliveryResult;
import com.personal.baton.application.calendar.port.out.CalendarSnapshotClient.Outcome;
import com.personal.baton.domain.workspace.Season;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

@Tag("calendar-metadata-crossservice")
class CalendarSeasonMetadataConsumerContractTest {

    @Test
    @DisplayName("실제 CAL은 BATON 시즌 이름 변경·중복·역순·충돌을 처리하고 일정과 구독을 유지한다")
    void deliversSeasonNamesToPublishedCalendar() throws Exception {
        Schema schema;
        try (InputStream input = getClass().getResourceAsStream(
                "/baton-cal/season-calendar-metadata.v1.schema.json")) {
            assertThat(input).isNotNull();
            schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(input);
        }
        schema.initializeValidators();
        String baseUrl = System.getenv("BATON_CAL_LIVE_BASE_URL");
        String bearerToken = System.getenv("BATON_CAL_LIVE_BEARER_TOKEN");
        RestClient internal = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeaders(headers -> headers.setBearerAuth(bearerToken))
                .build();
        RestClientCalendarClient client = new RestClientCalendarClient(RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeaders(headers -> headers.setBearerAuth(bearerToken))
                .requestInterceptor((request, body, execution) -> {
                    if (request.getMethod() == HttpMethod.PUT) {
                        assertThat(schema.validate(new String(body, StandardCharsets.UTF_8), InputFormat.JSON)).isEmpty();
                    }
                    return execution.execute(request, body);
                })
                .build());
        RestClient publicClient = RestClient.builder().baseUrl(baseUrl).build();
        Season season = Season.create(UUID.randomUUID(), UUID.randomUUID(), "개발 시즌",
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 11, 30));
        var initial = new CalendarSeasonMetadata(season.getId(), 0, season.getName());
        assertDelivered(client.deliver(initial), "SEASON_METADATA_ACCEPTED");
        assertDelivered(client.deliver(initial), "SEASON_METADATA_ACCEPTED");

        Instant updatedAt = Instant.parse("2026-08-30T00:00:00Z");
        assertDelivered(client.deliver(new CalendarSnapshot(
                UUID.randomUUID(), updatedAt, UUID.randomUUID(), season.getId(), 0,
                CalendarSnapshot.Status.ACTIVE, "개발 모임", null, null,
                new CalendarSnapshot.AllDay(season.getStartDate(), season.getStartDate().plusDays(1)), updatedAt
        )), "APPLIED");
        SubscriptionCredential credential = internal.post().uri("/internal/api/v1/subscriptions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new SubscriptionRequest(season.getId()))
                .retrieve().body(SubscriptionCredential.class);
        assertThat(credential).isNotNull();
        var before = publicClient.get().uri("/calendars/v1/{token}.ics", credential.token())
                .retrieve().toEntity(String.class);
        assertThat(before.getBody()).contains("X-WR-CALNAME:개발 시즌\r\n");

        season.update("가을 시즌", season.getStartDate(), season.getEndDate());
        var changed = new CalendarSeasonMetadata(season.getId(), 2, season.getName());
        assertDelivered(client.deliver(changed), "SEASON_METADATA_ACCEPTED");
        assertDelivered(client.deliver(changed), "SEASON_METADATA_ACCEPTED");
        assertDelivered(client.deliver(initial), "STALE");
        DeliveryResult conflict = client.deliver(new CalendarSeasonMetadata(season.getId(), 2, "다른 이름"));
        assertThat(conflict.outcome()).isEqualTo(Outcome.PERMANENT_FAILURE);
        assertThat(conflict.code()).isEqualTo("SEASON_METADATA_REVISION_CONFLICT");

        var after = publicClient.get().uri("/calendars/v1/{token}.ics", credential.token())
                .header(HttpHeaders.IF_NONE_MATCH, before.getHeaders().getETag())
                .retrieve().toEntity(String.class);
        assertThat(after.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(after.getHeaders().getETag()).isNotEqualTo(before.getHeaders().getETag());
        assertThat(after.getBody()).isEqualTo(before.getBody().replace(
                "X-WR-CALNAME:개발 시즌\r\n", "X-WR-CALNAME:가을 시즌\r\n"
        ));
    }

    private static void assertDelivered(DeliveryResult result, String code) {
        assertThat(result.outcome()).isEqualTo(Outcome.DELIVERED);
        assertThat(result.code()).isEqualTo(code);
    }

    private record SubscriptionRequest(UUID seasonId) {
    }

    private record SubscriptionCredential(String token) {
    }
}
