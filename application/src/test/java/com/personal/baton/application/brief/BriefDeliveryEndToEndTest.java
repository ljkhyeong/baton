package com.personal.baton.application.brief;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Tag("crossservice")
@Testcontainers
class BriefDeliveryEndToEndTest {

    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration CONDITION_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(3);
    private static final UUID SIGNAL_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000002401"
    );
    private static final UUID WORKSPACE_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000002402"
    );
    private static final UUID SEASON_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000002403"
    );
    private static final UUID REVISION_ONE_EVENT_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000002411"
    );
    private static final UUID REVISION_TWO_EVENT_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000002412"
    );
    private static final UUID REVISION_THREE_EVENT_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000002413"
    );
    private static final String SOURCE_REFERENCE = "baton-continuity:" + SIGNAL_ID;
    private static final Instant OCCURRED_AT = Instant.parse("2026-08-27T03:00:00Z");

    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("baton_brief_cross_service")
            .withUsername("baton")
            .withPassword("password");

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            "postgres:18.4-alpine"
    ).withDatabaseName("baton_brief_cross_service")
            .withUsername("brief")
            .withPassword("brief");

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(REQUEST_TIMEOUT)
            .build();

    @TempDir
    private Path tempDirectory;

    @DisplayName("BATON outbox가 장애와 동일 재전달을 거쳐 BRIEF 투영으로 수렴한다")
    @Test
    void deliversOutboxAcrossActualServiceRuntimes() throws Exception {
        Path batonJar = requiredJar("baton.boot.jar");
        Path briefJar = requiredJar("brief.boot.jar");
        int[] ports = availablePorts();
        int batonPort = ports[0];
        int briefPort = ports[1];
        JdbcTemplate jdbcTemplate = jdbcTemplate();

        try (ServiceRuntime baton = ServiceRuntime.start(
                "BATON",
                batonJar,
                batonPort,
                tempDirectory.resolve("baton"),
                Map.ofEntries(
                        Map.entry("SPRING_DATASOURCE_URL", MYSQL.getJdbcUrl()),
                        Map.entry("SPRING_DATASOURCE_USERNAME", MYSQL.getUsername()),
                        Map.entry("SPRING_DATASOURCE_PASSWORD", MYSQL.getPassword()),
                        Map.entry("BATON_BRIEF_DELIVERY_ENABLED", "true"),
                        Map.entry("BATON_BRIEF_BASE_URL", "http://127.0.0.1:" + briefPort),
                        Map.entry("BATON_BRIEF_CONNECT_TIMEOUT", "PT0.2S"),
                        Map.entry("BATON_BRIEF_READ_TIMEOUT", "PT2S"),
                        Map.entry("BATON_BRIEF_DISPATCH_INTERVAL", "PT0.2S")
                )
        )) {
            baton.awaitHealthy(httpClient);
            insertOutboxRevisions(jdbcTemplate);

            await("BRIEF 장애를 재시도 상태로 기록", () ->
                    outboxAttempts(jdbcTemplate, 1) > 0
                            && "PENDING".equals(outboxValue(
                                    jdbcTemplate,
                                    1,
                                    "delivery_status",
                                    String.class
                            ))
                            && "BRIEF_NETWORK_FAILURE".equals(outboxValue(
                                    jdbcTemplate,
                                    1,
                                    "last_error_code",
                                    String.class
                            ))
            );
            assertThat(outboxAttempts(jdbcTemplate, 2)).isZero();
            assertThat(outboxAttempts(jdbcTemplate, 3)).isZero();
            postponeRevision(jdbcTemplate, 2);
            postponeRevision(jdbcTemplate, 3);

            try (ServiceRuntime brief = ServiceRuntime.start(
                    "BRIEF",
                    briefJar,
                    briefPort,
                    tempDirectory.resolve("brief"),
                    Map.ofEntries(
                            Map.entry("SPRING_DATASOURCE_URL", POSTGRES.getJdbcUrl()),
                            Map.entry("SPRING_DATASOURCE_USERNAME", POSTGRES.getUsername()),
                            Map.entry("SPRING_DATASOURCE_PASSWORD", POSTGRES.getPassword())
                    )
            )) {
                brief.awaitHealthy(httpClient);
                awaitDelivered(jdbcTemplate, 1, "HTTP_202");
                int attemptsAfterFirstDelivery = outboxAttempts(jdbcTemplate, 1);
                JsonNode firstReceipt = getJson(
                        briefPort,
                        "/api/v1/events/" + REVISION_ONE_EVENT_ID + "/receipt"
                );
                assertThat(firstReceipt.path("processingOutcome").asText()).isEqualTo("APPLIED");
                assertThat(firstReceipt.path("sourceSeverity").asText()).isEqualTo("WARNING");

                simulateLostResponse(jdbcTemplate, 1);
                awaitDelivered(jdbcTemplate, 1, "HTTP_200");
                assertThat(outboxAttempts(jdbcTemplate, 1))
                        .isEqualTo(attemptsAfterFirstDelivery + 1);
                assertThat(getJson(
                        briefPort,
                        "/api/v1/events/" + REVISION_ONE_EVENT_ID + "/receipt"
                )).isEqualTo(firstReceipt);

                releaseRevision(jdbcTemplate, 2);
                awaitDelivered(jdbcTemplate, 2, "HTTP_202");
                JsonNode activeItem = getCurrentAttentionItem(briefPort);
                assertThat(activeItem.path("severity").asText()).isEqualTo("HIGH");
                assertThat(activeItem.path("status").asText()).isEqualTo("ACTIVE");
                assertThat(activeItem.path("aggregateRevision").asLong()).isEqualTo(2);

                releaseRevision(jdbcTemplate, 3);
                awaitDelivered(jdbcTemplate, 3, "HTTP_202");
                JsonNode resolvedItem = getCurrentAttentionItem(briefPort);
                assertThat(resolvedItem.path("status").asText()).isEqualTo("RESOLVED");
                assertThat(resolvedItem.path("aggregateRevision").asLong()).isEqualTo(3);
                assertThat(getJson(
                        briefPort,
                        "/api/v1/events/" + REVISION_THREE_EVENT_ID + "/receipt"
                ).path("processingOutcome").asText()).isEqualTo("APPLIED");
            }
        }
    }

    private void insertOutboxRevisions(JdbcTemplate jdbcTemplate) {
        var transactionManager = new DataSourceTransactionManager(
                jdbcTemplate.getDataSource()
        );
        new TransactionTemplate(transactionManager).executeWithoutResult(ignored -> {
            insertOutbox(jdbcTemplate, REVISION_THREE_EVENT_ID, 3, "CRITICAL", "RESOLVED");
            insertOutbox(jdbcTemplate, REVISION_TWO_EVENT_ID, 2, "CRITICAL", "ACTIVE");
            insertOutbox(jdbcTemplate, REVISION_ONE_EVENT_ID, 1, "WARNING", "ACTIVE");
        });
    }

    private void insertOutbox(
            JdbcTemplate jdbcTemplate,
            UUID eventId,
            long revision,
            String severity,
            String state
    ) {
        Instant availableAt = Instant.now().minusSeconds(1).truncatedTo(ChronoUnit.MICROS);
        jdbcTemplate.update(
                """
                INSERT INTO brief_continuity_outbox (
                    event_id, signal_id, workspace_id, season_id, event_type,
                    event_version, source_severity, source_reference,
                    aggregate_revision, occurred_at, event_state, available_at
                ) VALUES (
                    UUID_TO_BIN(?), UUID_TO_BIN(?), UUID_TO_BIN(?), UUID_TO_BIN(?),
                    'ROLE_UNASSIGNED', 2, ?, ?, ?, ?, ?, ?
                )
                """,
                eventId.toString(),
                SIGNAL_ID.toString(),
                WORKSPACE_ID.toString(),
                SEASON_ID.toString(),
                severity,
                SOURCE_REFERENCE,
                revision,
                utc(OCCURRED_AT.plusSeconds(revision)),
                state,
                utc(availableAt)
        );
    }

    private void postponeRevision(JdbcTemplate jdbcTemplate, long revision) {
        jdbcTemplate.update(
                """
                UPDATE brief_continuity_outbox
                SET available_at = ?
                WHERE signal_id = UUID_TO_BIN(?) AND aggregate_revision = ?
                """,
                utc(Instant.now().plus(Duration.ofHours(1))),
                SIGNAL_ID.toString(),
                revision
        );
    }

    private void releaseRevision(JdbcTemplate jdbcTemplate, long revision) {
        jdbcTemplate.update(
                """
                UPDATE brief_continuity_outbox
                SET available_at = ?
                WHERE signal_id = UUID_TO_BIN(?) AND aggregate_revision = ?
                """,
                utc(Instant.now().minusSeconds(1)),
                SIGNAL_ID.toString(),
                revision
        );
    }

    private void simulateLostResponse(JdbcTemplate jdbcTemplate, long revision) {
        jdbcTemplate.update(
                """
                UPDATE brief_continuity_outbox
                SET delivery_status = 'PENDING',
                    available_at = ?,
                    completed_at = NULL,
                    result_code = NULL
                WHERE signal_id = UUID_TO_BIN(?) AND aggregate_revision = ?
                """,
                utc(Instant.now().minusSeconds(1)),
                SIGNAL_ID.toString(),
                revision
        );
    }

    private void awaitDelivered(
            JdbcTemplate jdbcTemplate,
            long revision,
            String expectedResultCode
    ) {
        await("리비전 " + revision + " 전달 완료", () ->
                "DELIVERED".equals(outboxValue(
                        jdbcTemplate,
                        revision,
                        "delivery_status",
                        String.class
                )) && expectedResultCode.equals(outboxValue(
                        jdbcTemplate,
                        revision,
                        "result_code",
                        String.class
                ))
        );
    }

    private int outboxAttempts(JdbcTemplate jdbcTemplate, long revision) {
        return outboxValue(jdbcTemplate, revision, "attempt_count", Integer.class);
    }

    private <T> T outboxValue(
            JdbcTemplate jdbcTemplate,
            long revision,
            String column,
            Class<T> type
    ) {
        return jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM brief_continuity_outbox "
                        + "WHERE signal_id = UUID_TO_BIN(?) AND aggregate_revision = ?",
                type,
                SIGNAL_ID.toString(),
                revision
        );
    }

    private JsonNode getCurrentAttentionItem(int port) throws Exception {
        String path = "/api/v1/workspaces/" + WORKSPACE_ID
                + "/seasons/" + SEASON_ID
                + "/attention-items/current?eventType=ROLE_UNASSIGNED&sourceReference="
                + URLEncoder.encode(SOURCE_REFERENCE, StandardCharsets.UTF_8);
        return getJson(port, path);
    }

    private JsonNode getJson(int port, String path) throws Exception {
        HttpResponse<String> response = httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + port + path))
                        .timeout(REQUEST_TIMEOUT)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(response.statusCode()).as(path).isEqualTo(200);
        return jsonMapper.readTree(response.body());
    }

    private JdbcTemplate jdbcTemplate() {
        return new JdbcTemplate(new DriverManagerDataSource(
                MYSQL.getJdbcUrl(),
                MYSQL.getUsername(),
                MYSQL.getPassword()
        ));
    }

    private static Path requiredJar(String propertyName) {
        String configuredPath = System.getProperty(propertyName);
        assertThat(configuredPath).as(propertyName + " 실행 JAR 경로").isNotBlank();
        Path jar = Path.of(configuredPath).toAbsolutePath().normalize();
        assertThat(jar).as(propertyName + " 실행 JAR").isRegularFile();
        return jar;
    }

    private static int[] availablePorts() throws IOException {
        try (ServerSocket first = new ServerSocket(0, 0, InetAddress.getLoopbackAddress());
                ServerSocket second = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            return new int[]{first.getLocalPort(), second.getLocalPort()};
        }
    }

    private static void await(String description, BooleanSupplier condition) {
        Instant deadline = Instant.now().plus(CONDITION_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(description + " 대기 중 중단됐습니다", exception);
            }
        }
        throw new IllegalStateException(description + "을 제한 시간 안에 확인하지 못했습니다");
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static final class ServiceRuntime implements AutoCloseable {

        private final String name;
        private final Process process;
        private final int port;
        private final Path logFile;

        private ServiceRuntime(String name, Process process, int port, Path logFile) {
            this.name = name;
            this.process = process;
            this.port = port;
            this.logFile = logFile;
        }

        static ServiceRuntime start(
                String name,
                Path jar,
                int port,
                Path runtimeDirectory,
                Map<String, String> environment
        ) throws IOException {
            Files.createDirectories(runtimeDirectory);
            Path logFile = runtimeDirectory.resolve("application.log");
            ProcessBuilder processBuilder = new ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-jar",
                    jar.toString()
            );
            processBuilder.directory(runtimeDirectory.toFile());
            processBuilder.redirectErrorStream(true);
            processBuilder.redirectOutput(logFile.toFile());
            processBuilder.environment().putAll(environment);
            processBuilder.environment().put("SERVER_ADDRESS", "127.0.0.1");
            processBuilder.environment().put("SERVER_PORT", Integer.toString(port));
            return new ServiceRuntime(name, processBuilder.start(), port, logFile);
        }

        void awaitHealthy(HttpClient httpClient) {
            Instant deadline = Instant.now().plus(STARTUP_TIMEOUT);
            while (Instant.now().isBefore(deadline)) {
                if (!process.isAlive()) {
                    throw startupFailure("기동 전에 종료됐습니다");
                }
                try {
                    HttpResponse<String> response = httpClient.send(
                            HttpRequest.newBuilder()
                                    .uri(URI.create(
                                            "http://127.0.0.1:" + port + "/actuator/health"
                                    ))
                                    .timeout(REQUEST_TIMEOUT)
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofString()
                    );
                    if (response.statusCode() == 200) {
                        return;
                    }
                } catch (IOException ignored) {
                    // 기동 중에는 연결 거부가 정상이다.
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw startupFailure("기동 대기 중 중단됐습니다", exception);
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw startupFailure("기동 대기 중 중단됐습니다", exception);
                }
            }
            throw startupFailure("제한 시간 안에 상태 확인에 성공하지 못했습니다");
        }

        private IllegalStateException startupFailure(String message) {
            return startupFailure(message, null);
        }

        private IllegalStateException startupFailure(String message, Exception cause) {
            String logs;
            try {
                logs = Files.readString(logFile);
            } catch (IOException exception) {
                logs = "로그를 읽지 못했습니다: " + exception.getMessage();
            }
            return new IllegalStateException(name + "이 " + message + "\n" + logs, cause);
        }

        @Override
        public void close() {
            process.destroy();
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(5, TimeUnit.SECONDS);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
    }
}
