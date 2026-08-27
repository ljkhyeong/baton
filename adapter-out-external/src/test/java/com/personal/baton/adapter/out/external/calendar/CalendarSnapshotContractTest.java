package com.personal.baton.adapter.out.external.calendar;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import com.personal.baton.application.calendar.CalendarSnapshot;
import com.personal.baton.application.calendar.CalendarSnapshotFactory;
import com.personal.baton.domain.workspace.Routine;
import com.personal.baton.domain.workspace.RoutineExecution;
import com.personal.baton.domain.workspace.RoutinePhase;
import com.personal.baton.domain.workspace.Season;
import com.personal.baton.domain.workspace.SeasonRound;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class CalendarSnapshotContractTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final CalendarSnapshotFactory FACTORY = new CalendarSnapshotFactory();

    private static Schema schema;

    @BeforeAll
    static void loadPinnedContract() throws Exception {
        byte[] schemaBytes = resourceBytes("baton-cal/schedule-snapshot.v1.schema.json");
        Properties pin = new Properties();
        try (InputStream input = resource("baton-cal/pin.properties")) {
            pin.load(input);
        }
        assertThat(HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(schemaBytes)
        )).isEqualTo(pin.getProperty("schemaSha256"));

        SchemaRegistry registry = SchemaRegistry.withDefaultDialect(
                SpecificationVersion.DRAFT_2020_12,
                builder -> builder.schemaRegistryConfig(
                        SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build()
                )
        );
        schema = registry.getSchema(new java.io.ByteArrayInputStream(schemaBytes));
        schema.initializeValidators();
    }

    @Test
    @DisplayName("수동 회차와 자동 회차와 루틴 마감을 안정 계약 시간 형태로 직렬화한다")
    void serializesBatonCalendarSourcesToPinnedContract() throws Exception {
        UUID seasonId = UUID.fromString("f5316f93-d49e-4230-b1d0-9e9c2d079819");
        Season season = Season.create(
                seasonId,
                UUID.randomUUID(),
                "2026 하반기",
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 12, 31),
                "Asia/Seoul"
        );
        SeasonRound manualRound = SeasonRound.create(
                UUID.fromString("c2000000-0000-4000-8000-000000000001"),
                seasonId,
                "수동 운영 회차",
                LocalDate.of(2026, 8, 22)
        );
        SeasonRound automaticRound = SeasonRound.createAutomatic(
                UUID.fromString("b2000000-0000-4000-8000-000000000001"),
                seasonId,
                "정기 운영 회차",
                LocalDate.of(2026, 8, 19),
                Instant.parse("2026-08-19T11:00:00Z")
        );
        Routine routine = Routine.create(
                UUID.randomUUID(),
                seasonId,
                "발표 자료 제출 마감",
                RoutinePhase.BEFORE,
                "모임 5일 전",
                UUID.randomUUID(),
                "BATON이 확정한 루틴 마감입니다.",
                -5,
                LocalTime.of(21, 0)
        );
        RoutineExecution execution = RoutineExecution.snapshot(
                UUID.fromString("a2000000-0000-4000-8000-000000000001"),
                manualRound.getId(),
                routine,
                manualRound.getMeetingDate(),
                season.getZoneId()
        );

        List<JsonNode> documents = List.of(
                json(FACTORY.fromRound(
                        UUID.randomUUID(),
                        Instant.parse("2026-08-11T04:00:00Z"),
                        season,
                        manualRound
                ).numbered(0)),
                json(FACTORY.fromRound(
                        UUID.randomUUID(),
                        Instant.parse("2026-08-11T03:00:00Z"),
                        season,
                        automaticRound
                ).numbered(1)),
                json(FACTORY.fromExecution(
                        UUID.randomUUID(),
                        Instant.parse("2026-08-11T02:00:00Z"),
                        manualRound,
                        execution
                ).orElseThrow().numbered(2))
        );

        for (JsonNode document : documents) {
            assertThat(schema.validate(document.toString(), InputFormat.JSON)).isEmpty();
        }
        assertThat(documents.get(0).path("time").path("type").asString()).isEqualTo("ALL_DAY");
        assertThat(documents.get(0).path("time").path("endDate").asString())
                .isEqualTo("2026-08-23");
        assertThat(documents.get(1).path("time").path("type").asString())
                .isEqualTo("ZONED_LOCAL_POINT");
        assertThat(documents.get(1).path("time").path("atLocal").asString())
                .isEqualTo("2026-08-19T20:00:00");
        assertThat(documents.get(2).path("time").path("type").asString())
                .isEqualTo("UTC_POINT");
        assertThat(documents.get(2).path("time").path("atInstant").asString())
                .isEqualTo("2026-08-17T12:00:00Z");
    }

    @Test
    @DisplayName("회차 보관은 회차와 마감 스냅샷을 취소 상태로 만든다")
    void mapsArchivedRoundAndExecutionToCancelledSnapshots() {
        UUID seasonId = UUID.randomUUID();
        Season season = Season.create(
                seasonId,
                UUID.randomUUID(),
                "시즌",
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 31)
        );
        SeasonRound round = SeasonRound.create(
                UUID.randomUUID(),
                seasonId,
                "보관 회차",
                LocalDate.of(2026, 8, 22)
        );
        Routine routine = Routine.create(
                UUID.randomUUID(),
                seasonId,
                "마감",
                RoutinePhase.BEFORE,
                "전날",
                UUID.randomUUID(),
                "준비",
                -1,
                LocalTime.of(20, 0)
        );
        RoutineExecution execution = RoutineExecution.snapshot(
                UUID.randomUUID(),
                round.getId(),
                routine,
                round.getMeetingDate(),
                season.getZoneId()
        );
        Instant archivedAt = Instant.parse("2026-08-12T00:00:00Z");
        round.updateArchive(true, archivedAt);

        CalendarSnapshot roundSnapshot = FACTORY.fromRound(
                UUID.randomUUID(), archivedAt, season, round
        ).numbered(3);
        CalendarSnapshot executionSnapshot = FACTORY.fromExecution(
                UUID.randomUUID(), archivedAt, round, execution
        ).orElseThrow().numbered(4);

        assertThat(roundSnapshot.status()).isEqualTo(CalendarSnapshot.Status.CANCELLED);
        assertThat(executionSnapshot.status()).isEqualTo(CalendarSnapshot.Status.CANCELLED);
    }

    private static JsonNode json(CalendarSnapshot snapshot) throws Exception {
        return OBJECT_MAPPER.valueToTree(CalendarSnapshotRequest.from(snapshot));
    }

    private static byte[] resourceBytes(String path) throws IOException {
        try (InputStream input = resource(path)) {
            return input.readAllBytes();
        }
    }

    private static InputStream resource(String path) {
        return CalendarSnapshotContractTest.class.getClassLoader().getResourceAsStream(path);
    }
}
