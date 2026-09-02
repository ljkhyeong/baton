package com.personal.baton.application.calendar;

import com.personal.baton.application.workspace.port.in.WorkspaceContract;

import com.personal.baton.BatonApplication;
import com.personal.baton.adapter.out.external.calendar.RestClientCalendarClient;
import com.personal.baton.application.calendar.port.in.MaintainCalendarSeasonMetadataUseCase;
import com.personal.baton.application.calendar.port.in.MaintainCalendarSeasonMetadataUseCase.Mode;
import com.personal.baton.application.calendar.port.out.CalendarOutboxPort;
import com.personal.baton.application.calendar.port.out.CalendarSeasonMetadataClient;
import com.personal.baton.application.calendar.port.out.CalendarSnapshotClient;
import com.personal.baton.application.calendar.port.out.CalendarSnapshotClient.DeliveryResult;
import com.personal.baton.application.workspace.port.in.WorkspaceLifecycleUseCase;
import com.personal.baton.application.workspace.port.in.WorkspaceLifecycleCommands.CreateWorkspaceCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceLifecycleCommands.CreateNextSeasonCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceLifecycleCommands.UpdateSeasonCommand;
import java.time.Clock;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@Tag("usecase")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = BatonApplication.class, properties = {
        "baton.workspace.creation-key=pilot-operator-key-0000000000000001",
        "baton.workspace.recovery-key=pilot-recovery-key-0000000000000002",
        "baton.calendar.capture-enabled=true",
        "baton.calendar.season-metadata-enabled=true",
        "baton.calendar.delivery-enabled=false",
        "baton.round-automation.poll-interval=PT24H"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CalendarSeasonMetadataOutboxTest {

    private static final String CREATION_KEY = "pilot-operator-key-0000000000000001";
    private static final LocalDate START = LocalDate.of(2026, 8, 1);
    private static final LocalDate END = LocalDate.of(2026, 8, 31);

    @Container
    @ServiceConnection
    private static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:b3b90af2a6552ae30c266fdb7d5dd55f3afb72404bb78d37fe8a23eb857fd3fb"
    ).withDatabaseName("baton_calendar_metadata").withUsername("baton").withPassword("password");

    @Autowired private WorkspaceLifecycleUseCase workspace;
    @Autowired private CalendarOutboxPort outbox;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private RestClientCalendarClient.Factory clientFactory;
    @Autowired private MaintainCalendarSeasonMetadataUseCase maintenance;
    @TempDir private Path composeLogs;

    @Test
    @Tag("calendar-outbox-crossservice")
    @DisplayName("원본 이름을 CAL에 전달하고 실제 백업 복원 뒤 같은 개정 번호로 최신 이름을 복구한다")
    void deliversSourceChangesThroughOutboxToRealCalendar() throws Exception {
        String baseUrl = System.getenv("BATON_CAL_LIVE_BASE_URL");
        String token = System.getenv("BATON_CAL_LIVE_BEARER_TOKEN");
        var client = clientFactory.create(URI.create(baseUrl), token, Duration.ofSeconds(2), Duration.ofSeconds(5));
        var internal = RestClient.builder().baseUrl(baseUrl)
                .defaultHeaders(headers -> headers.setBearerAuth(token)).build();
        var publicClient = RestClient.builder().baseUrl(baseUrl).build();
        var created = workspace.createWorkspace(UUID.randomUUID().toString(), CREATION_KEY, command("처음 시즌"));
        assertThat(dispatcher(client, client, Instant.now().plusSeconds(1)).dispatchPending().deliveredCount()).isOne();
        var subscription = internal.post().uri("/internal/api/v1/subscriptions")
                .contentType(MediaType.APPLICATION_JSON).body(new SubscriptionRequest(created.seasonId()))
                .retrieve().body(Subscription.class);
        assertThat(subscription).isNotNull();
        String initial = publicClient.get().uri("/calendars/v1/{token}.ics", subscription.token())
                .retrieve().body(String.class);
        assertThat(initial).contains("X-WR-CALNAME:처음 시즌\r\n");

        String generation = System.getenv("BATON_CAL_SUBSCRIPTION_GENERATION");
        compose(generation, false, "exec", "-T", "postgres", "pg_dump", "-U", "baton_cal_smoke",
                "-d", "baton_cal_smoke", "-Fc", "-f", "/tmp/calendar-metadata-recovery.dump");

        workspace.updateSeason(created.teamId(), created.seasonId(), created.accessKey(),
                new UpdateSeasonCommand("바뀐 시즌", START, END));
        assertThat(dispatcher(client, client, Instant.now().plusSeconds(1)).dispatchPending().deliveredCount()).isOne();
        assertThat(publicClient.get().uri("/calendars/v1/{token}.ics", subscription.token())
                .retrieve().body(String.class))
                .isEqualTo(initial.replace("X-WR-CALNAME:처음 시즌\r\n", "X-WR-CALNAME:바뀐 시즌\r\n"));
        assertThat(jdbc.queryForList("SELECT delivery_status FROM calendar_season_metadata_outbox", String.class))
                .containsExactly("DELIVERED", "DELIVERED");

        var latestBefore = jdbc.queryForMap("SELECT id, display_name, occurred_at FROM calendar_season_metadata_outbox ORDER BY id DESC LIMIT 1");
        compose(generation, false, "stop", "app");
        String recoveryGeneration = UUID.randomUUID().toString();
        compose(recoveryGeneration, true, "exec", "-T", "postgres", "pg_restore", "-U", "baton_cal_smoke",
                "--dbname=postgres", "--clean", "--create", "--exit-on-error", "/tmp/calendar-metadata-recovery.dump");
        String restoredUrl = startCalendar(recoveryGeneration, true);
        var restoredInternal = RestClient.builder().baseUrl(restoredUrl)
                .defaultHeaders(headers -> headers.setBearerAuth(token)).build();
        var restoredPublic = RestClient.create(restoredUrl);
        assertThat(restoredPublic.get().uri("/calendars/v1/{token}.ics", subscription.token())
                .<Integer>exchange((request, response) -> response.getStatusCode().value())).isEqualTo(404);
        assertThat(restoredInternal.post().uri("/internal/api/v1/subscriptions")
                .contentType(MediaType.APPLICATION_JSON).body(new SubscriptionRequest(created.seasonId()))
                .<Integer>exchange((request, response) -> response.getStatusCode().value())).isEqualTo(503);

        var replay = maintenance.maintain(Mode.REPLAY);
        assertThat(replay.appendedCount()).isZero();
        assertThat(replay.requeuedCount()).isOne();
        assertThat(jdbc.queryForMap("SELECT id, display_name, occurred_at FROM calendar_season_metadata_outbox ORDER BY id DESC LIMIT 1"))
                .isEqualTo(latestBefore);
        var recoveryClient = clientFactory.create(URI.create(restoredUrl), token, Duration.ofSeconds(2), Duration.ofSeconds(5));
        assertThat(dispatcher(recoveryClient, recoveryClient, Instant.now().plusSeconds(1)).dispatchPending().deliveredCount()).isOne();
        assertThat(restoredInternal.post().uri("/internal/api/v1/subscriptions")
                .contentType(MediaType.APPLICATION_JSON).body(new SubscriptionRequest(created.seasonId()))
                .<Integer>exchange((request, response) -> response.getStatusCode().value())).isEqualTo(503);

        String recoveredUrl = startCalendar(recoveryGeneration, false);
        var recoveredInternal = RestClient.builder().baseUrl(recoveredUrl)
                .defaultHeaders(headers -> headers.setBearerAuth(token)).build();
        var recovered = recoveredInternal.post().uri("/internal/api/v1/subscriptions")
                .contentType(MediaType.APPLICATION_JSON).body(new SubscriptionRequest(created.seasonId()))
                .retrieve().body(Subscription.class);
        assertThat(recovered).isNotNull();
        assertThat(RestClient.create(recoveredUrl).get().uri("/calendars/v1/{token}.ics", recovered.token())
                .retrieve().body(String.class))
                .isEqualTo(initial.replace("X-WR-CALNAME:처음 시즌\r\n", "X-WR-CALNAME:바뀐 시즌\r\n"));
        assertThat(RestClient.create(recoveredUrl).get().uri("/calendars/v1/{token}.ics", subscription.token())
                .<Integer>exchange((request, response) -> response.getStatusCode().value())).isEqualTo(404);
    }

    private String startCalendar(String generation, boolean recoveryMode) throws Exception {
        compose(generation, recoveryMode, "up", "--detach", "--wait", "--wait-timeout", "120", "app");
        String port = compose(generation, recoveryMode, "port", "app", "8081").strip();
        var readiness = HttpRequest.newBuilder(URI.create("http://" + port + "/actuator/health/readiness"))
                .timeout(Duration.ofSeconds(2)).build();
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
            await().atMost(Duration.ofMinutes(3)).ignoreExceptions().until(() ->
                    client.send(readiness, HttpResponse.BodyHandlers.discarding()).statusCode() == 200);
        }
        return "http://" + compose(generation, recoveryMode, "port", "app", "8080").strip();
    }

    private String compose(String generation, boolean recoveryMode, String... arguments) throws Exception {
        String project = System.getenv("BATON_CAL_LIVE_COMPOSE_PROJECT");
        // DB 복원 명령은 이 스크립트가 만든 일회용 Compose 프로젝트로 제한한다.
        assertThat(project).startsWith("baton-cal-consumer-");
        var command = new ArrayList<>(List.of("docker", "compose", "--project-name", project,
                "--file", System.getenv("BATON_CAL_LIVE_COMPOSE_FILE")));
        command.addAll(List.of(arguments));
        Path output = Files.createTempFile(composeLogs, "compose-", ".log");
        var builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile());
        builder.environment().put("BATON_CAL_SUBSCRIPTION_GENERATION", generation);
        builder.environment().put("BATON_CAL_RECOVERY_MODE", Boolean.toString(recoveryMode));
        Process process = builder.start();
        boolean finished = process.waitFor(180, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
        }
        assertThat(finished).as("Compose 명령 제한 시간: %s", List.of(arguments)).isTrue();
        String result = Files.readString(output);
        assertThat(process.exitValue()).as("Compose 명령 결과: %s", result).isZero();
        return result;
    }

    @BeforeEach
    void clearDeliveries() {
        jdbc.update("DELETE FROM calendar_season_metadata_outbox");
        jdbc.update("DELETE FROM calendar_snapshot_outbox");
    }

    @Test
    @DisplayName("생성 재요청과 날짜 수정은 중복 기록 없이 이름 변경과 다음 시즌만 기록한다")
    void recordsCreationRenameAndSuccessor() {
        String key = UUID.randomUUID().toString();
        var command = command("여름 Season");
        var created = workspace.createWorkspace(key, CREATION_KEY, command);
        assertThat(workspace.createWorkspace(key, CREATION_KEY, command)).isEqualTo(created);
        workspace.updateSeason(created.teamId(), created.seasonId(), created.accessKey(),
                new UpdateSeasonCommand("여름 Season", START, END.minusDays(1)));
        workspace.updateSeason(created.teamId(), created.seasonId(), created.accessKey(),
                new UpdateSeasonCommand("여름 season", START, END));
        workspace.updateSeason(created.teamId(), created.seasonId(), created.accessKey(),
                new UpdateSeasonCommand("여름 season", START, END));
        var nextCommand = new CreateNextSeasonCommand(
                "가을 시즌", END.plusDays(1), END.plusMonths(3), List.of(), List.of()
        );
        String nextKey = UUID.randomUUID().toString();
        var next = workspace.createNextSeason(
                created.teamId(), created.seasonId(), nextKey, created.accessKey(), nextCommand
        );
        workspace.createNextSeason(created.teamId(), created.seasonId(), nextKey, created.accessKey(), nextCommand);

        assertThat(names()).containsExactly("여름 Season", "여름 season", "가을 시즌");
        assertThat(jdbc.queryForList(
                "SELECT BIN_TO_UUID(season_id) FROM calendar_season_metadata_outbox ORDER BY id", String.class
        )).containsExactly(created.seasonId().toString(), created.seasonId().toString(), next.season().id().toString());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM calendar_snapshot_outbox", Integer.class)).isZero();
    }

    @Test
    @DisplayName("원본 생성 트랜잭션이 취소되면 시즌 이름 아웃박스도 남지 않는다")
    void rollsBackWithSourceCreation() {
        int seasonsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM seasons", Integer.class);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            workspace.createWorkspace(UUID.randomUUID().toString(), CREATION_KEY, command("취소 시즌"));
            assertThat(names()).containsExactly("취소 시즌");
            status.setRollbackOnly();
        });

        assertThat(names()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM seasons", Integer.class)).isEqualTo(seasonsBefore);
    }

    @Test
    @DisplayName("이름 전달은 커밋과 임대 트랜잭션 밖에서 실행하고 응답 유실은 같은 개정 번호로 재시도한다")
    void dispatchesCommittedMetadataAndRetriesSameRevision() {
        var created = workspace.createWorkspace(UUID.randomUUID().toString(), CREATION_KEY, command("여름 시즌"));
        CalendarSeasonMetadataClient client = mock(CalendarSeasonMetadataClient.class);
        CalendarSnapshotClient snapshots = mock(CalendarSnapshotClient.class);
        when(client.deliver(any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            CalendarSeasonMetadata metadata = call.getArgument(0);
            assertThat(metadata.seasonId()).isEqualTo(created.seasonId());
            return DeliveryResult.retryable("CAL_NETWORK_FAILURE");
        }).thenReturn(DeliveryResult.delivered("SEASON_METADATA_ACCEPTED"));
        Instant now = Instant.now().plusSeconds(1);
        var first = dispatcher(snapshots, client, now).dispatchPending();
        assertThat(first.failedCount()).isOne();
        var firstRow = jdbc.queryForMap("SELECT id, attempt_count, delivery_status FROM calendar_season_metadata_outbox");
        assertThat(firstRow.get("delivery_status")).isEqualTo("PENDING");
        assertThat(dispatcher(snapshots, client, now.plusSeconds(9)).dispatchPending().claimedCount()).isZero();
        assertThat(dispatcher(snapshots, client, now.plusSeconds(10)).dispatchPending().deliveredCount()).isOne();
        var lastRow = jdbc.queryForMap("SELECT id, attempt_count, delivery_status FROM calendar_season_metadata_outbox");
        assertThat(lastRow.get("id")).isEqualTo(firstRow.get("id"));
        assertThat(lastRow.get("attempt_count")).isEqualTo(2);
        assertThat(lastRow.get("delivery_status")).isEqualTo("DELIVERED");
        verifyNoInteractions(snapshots);
    }

    @Test
    @DisplayName("일정과 이름 큐는 분리되고 이름 임대 재획득 뒤 이전 작업자의 결과는 반영하지 않는다")
    void separatesQueuesAndFencesExpiredLease() {
        UUID seasonId = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-30T00:00:00Z");
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            outbox.appendSeasonMetadataIfChanged(seasonId, "처음 이름", now);
            outbox.appendSeasonMetadataIfChanged(seasonId, "바뀐 이름", now);
        });
        assertThat(outbox.claimPending(10, now, Duration.ofMinutes(1), false)).isEmpty();
        var firstBatch = outbox.claimPending(10, now, Duration.ofMinutes(1), true);
        assertThat(firstBatch).hasSize(1);
        var first = firstBatch.getFirst();
        var reclaimed = outbox.claimPending(10, now.plusSeconds(61), Duration.ofMinutes(1), true).getFirst();
        assertThat(reclaimed.payload()).isEqualTo(first.payload());
        assertThat(outbox.markDelivered(first.payload(), first.leaseToken(), now.plusSeconds(62), "STALE")).isFalse();
        assertThat(outbox.markDelivered(reclaimed.payload(), reclaimed.leaseToken(), now.plusSeconds(62), "ACCEPTED")).isTrue();
        assertThat(outbox.claimPending(10, now.plusSeconds(63), Duration.ofMinutes(1), true))
                .singleElement().satisfies(delivery -> {
                    assertThat(delivery.payload().revision()).isGreaterThan(first.payload().revision());
                    assertThat(((CalendarSeasonMetadata) delivery.payload()).displayName()).isEqualTo("바뀐 이름");
                });
    }

    private CalendarOutboxDispatchService dispatcher(
            CalendarSnapshotClient snapshots, CalendarSeasonMetadataClient metadata, Instant now
    ) {
        return new CalendarOutboxDispatchService(
                outbox, snapshots, metadata, new CalendarCaptureState(true, true), Clock.fixed(now, ZoneOffset.UTC)
        );
    }

    private CreateWorkspaceCommand command(String name) {
        return new CreateWorkspaceCommand("이름 연동 팀", name, START, END, List.of("김팀원"));
    }

    private List<String> names() {
        return jdbc.queryForList("SELECT display_name FROM calendar_season_metadata_outbox ORDER BY id", String.class);
    }

    private record Subscription(String token) {
    }

    private record SubscriptionRequest(UUID seasonId) {
    }
}
