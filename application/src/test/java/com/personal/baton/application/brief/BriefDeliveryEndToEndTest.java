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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
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
    private static final String WORKSPACE_IDEMPOTENCY_KEY =
            "brief-cross-service-workspace-000001";
    private static final String ROLE_IDEMPOTENCY_KEY =
            "brief-cross-service-role-000000000001";
    private static final String BRIEF_BEARER_TOKEN =
            "brief-cross-service-bearer-token-000001";
    private static final String BRIEF_NEXT_BEARER_TOKEN =
            "brief-cross-service-bearer-token-000002";

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

    @DisplayName("BATON 원본 신호가 초기 정합화와 재전달을 거쳐 BRIEF로 수렴한다")
    @Test
    void deliversAuthoritativeSignalAcrossActualServiceRuntimes() throws Exception {
        Path batonJar = requiredJar("baton.boot.jar");
        Path briefJar = requiredJar("brief.boot.jar");
        int[] ports = availablePorts();
        int batonPort = ports[0];
        int briefPort = ports[1];
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate futureStart = today.plusDays(30);
        LocalDate endDate = today.plusDays(90);

        UUID workspaceId;
        UUID seasonId;
        UUID roleId;
        String accessKey;
        UUID memberId;
        try (ServiceRuntime batonSetup = ServiceRuntime.start(
                "BATON 원본 준비",
                batonJar,
                batonPort,
                tempDirectory.resolve("baton-setup"),
                batonEnvironment(briefPort, false)
        )) {
            batonSetup.awaitHealthy(httpClient);
            JsonNode createdWorkspace = requestJson(
                    batonPort,
                    "POST",
                    "/api/v1/workspaces",
                    Map.of("Idempotency-Key", WORKSPACE_IDEMPOTENCY_KEY),
                    """
                    {
                      "teamName": "BRIEF 교차 서비스 팀",
                      "seasonName": "BRIEF 교차 서비스 시즌",
                      "startDate": "%s",
                      "endDate": "%s",
                      "memberNames": ["김준호"]
                    }
                    """.formatted(futureStart, endDate),
                    201
            );
            workspaceId = UUID.fromString(createdWorkspace.path("teamId").asText());
            seasonId = UUID.fromString(createdWorkspace.path("seasonId").asText());
            accessKey = createdWorkspace.path("accessKey").asText();
            JsonNode workspace = requestJson(
                    batonPort,
                    "GET",
                    workspacePath(workspaceId, seasonId),
                    Map.of("X-Baton-Access-Key", accessKey),
                    null,
                    200
            );
            memberId = UUID.fromString(workspace.path("members").get(0).path("id").asText());
            JsonNode createdRole = requestJson(
                    batonPort,
                    "POST",
                    rolesPath(workspaceId, seasonId),
                    Map.of(
                            "Idempotency-Key", ROLE_IDEMPOTENCY_KEY,
                            "X-Baton-Access-Key", accessKey
                    ),
                    roleRequest(null),
                    201
            );
            roleId = UUID.fromString(createdRole.path("id").asText());
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM brief_continuity_outbox",
                    Long.class
            )).isEqualTo(1L);
        }

        clearBriefDerivedState(jdbcTemplate);

        try (ServiceRuntime baton = ServiceRuntime.start(
                "BATON",
                batonJar,
                batonPort,
                tempDirectory.resolve("baton-delivery"),
                batonEnvironment(briefPort, true)
        )) {
            baton.awaitHealthy(httpClient);
            JsonNode persistedWorkspace = requestJson(
                    batonPort,
                    "GET",
                    workspacePath(workspaceId, seasonId),
                    Map.of("X-Baton-Access-Key", accessKey),
                    null,
                    200
            );
            assertThat(persistedWorkspace.path("roles").get(0).path("id").asText())
                    .isEqualTo(roleId.toString());
            assertThat(persistedWorkspace.path("roles").get(0).path("currentMemberId").isNull())
                    .isTrue();

            await("초기 정합화로 ROLE_UNASSIGNED 생성", () ->
                    outboxCount(jdbcTemplate) == 1
            );
            Map<String, Object> initialOutbox = jdbcTemplate.queryForMap(
                    """
                    SELECT
                        BIN_TO_UUID(signal_id) AS signal_id,
                        BIN_TO_UUID(event_id) AS event_id,
                        source_reference,
                        source_severity,
                        event_state
                    FROM brief_continuity_outbox
                    WHERE workspace_id = UUID_TO_BIN(?)
                    AND season_id = UUID_TO_BIN(?)
                    AND event_type = 'ROLE_UNASSIGNED'
                    AND aggregate_revision = 1
                    """,
                    workspaceId.toString(),
                    seasonId.toString()
            );
            UUID signalId = UUID.fromString((String) initialOutbox.get("SIGNAL_ID"));
            UUID revisionOneEventId = UUID.fromString((String) initialOutbox.get("EVENT_ID"));
            String sourceReference = (String) initialOutbox.get("SOURCE_REFERENCE");
            assertThat(initialOutbox)
                    .containsEntry("SOURCE_SEVERITY", "WARNING")
                    .containsEntry("EVENT_STATE", "ACTIVE");

            await("BRIEF 장애를 재시도 상태로 기록", () ->
                    outboxAttempts(jdbcTemplate, signalId, 1) > 0
                            && "PENDING".equals(outboxValue(
                                    jdbcTemplate,
                                    signalId,
                                    1,
                                    "delivery_status",
                                    String.class
                            ))
                            && "BRIEF_NETWORK_FAILURE".equals(outboxValue(
                                    jdbcTemplate,
                                    signalId,
                                    1,
                                    "last_error_code",
                                    String.class
                            ))
            );

            try (ServiceRuntime brief = ServiceRuntime.start(
                    "BRIEF",
                    briefJar,
                    briefPort,
                    tempDirectory.resolve("brief"),
                    Map.ofEntries(
                            Map.entry("SPRING_DATASOURCE_URL", POSTGRES.getJdbcUrl()),
                            Map.entry("SPRING_DATASOURCE_USERNAME", POSTGRES.getUsername()),
                            Map.entry("SPRING_DATASOURCE_PASSWORD", POSTGRES.getPassword()),
                            Map.entry("BRIEF_EVENT_RECEIVER_AUTHENTICATION_REQUIRED", "true"),
                            Map.entry("BRIEF_EVENT_RECEIVER_BEARER_TOKEN", BRIEF_NEXT_BEARER_TOKEN),
                            Map.entry(
                                    "BRIEF_EVENT_RECEIVER_PREVIOUS_BEARER_TOKEN",
                                    BRIEF_BEARER_TOKEN
                            )
                    )
            )) {
                brief.awaitHealthy(httpClient);
                awaitDelivered(jdbcTemplate, signalId, 1, "HTTP_202");
                int attemptsAfterFirstDelivery = outboxAttempts(jdbcTemplate, signalId, 1);
                JsonNode firstReceipt = getJson(
                        briefPort,
                        "/api/v1/events/" + revisionOneEventId + "/receipt"
                );
                assertThat(firstReceipt.path("processingOutcome").asText()).isEqualTo("APPLIED");
                assertThat(firstReceipt.path("sourceSeverity").asText()).isEqualTo("WARNING");

                simulateLostResponse(jdbcTemplate, signalId, 1);
                awaitDelivered(jdbcTemplate, signalId, 1, "HTTP_200");
                assertThat(outboxAttempts(jdbcTemplate, signalId, 1))
                        .isEqualTo(attemptsAfterFirstDelivery + 1);
                assertThat(getJson(
                        briefPort,
                        "/api/v1/events/" + revisionOneEventId + "/receipt"
                )).isEqualTo(firstReceipt);

                requestJson(
                        batonPort,
                        "PUT",
                        "/api/v1/teams/" + workspaceId + "/seasons/" + seasonId,
                        Map.of("X-Baton-Access-Key", accessKey),
                        """
                        {
                          "name": "BRIEF 교차 서비스 시즌",
                          "startDate": "%s",
                          "endDate": "%s"
                        }
                        """.formatted(today.minusDays(1), endDate),
                        200
                );
                awaitDelivered(jdbcTemplate, signalId, 2, "HTTP_202");
                JsonNode activeItem = getCurrentAttentionItem(
                        briefPort,
                        workspaceId,
                        seasonId,
                        sourceReference
                );
                assertThat(activeItem.path("severity").asText()).isEqualTo("HIGH");
                assertThat(activeItem.path("status").asText()).isEqualTo("ACTIVE");
                assertThat(activeItem.path("aggregateRevision").asLong()).isEqualTo(2);

                requestJson(
                        batonPort,
                        "PUT",
                        rolesPath(workspaceId, seasonId) + "/" + roleId,
                        Map.of("X-Baton-Access-Key", accessKey),
                        roleRequest(memberId),
                        200
                );
                awaitDelivered(jdbcTemplate, signalId, 3, "HTTP_202");
                JsonNode resolvedItem = getCurrentAttentionItem(
                        briefPort,
                        workspaceId,
                        seasonId,
                        sourceReference
                );
                assertThat(resolvedItem.path("status").asText()).isEqualTo("RESOLVED");
                assertThat(resolvedItem.path("aggregateRevision").asLong()).isEqualTo(3);
                UUID revisionThreeEventId = outboxEventId(jdbcTemplate, signalId, 3);
                assertThat(getJson(
                        briefPort,
                        "/api/v1/events/" + revisionThreeEventId + "/receipt"
                ).path("processingOutcome").asText()).isEqualTo("APPLIED");
            }
        }
    }

    private static Map<String, String> batonEnvironment(int briefPort, boolean deliveryEnabled) {
        Map<String, String> environment = new HashMap<>();
        environment.put("SPRING_DATASOURCE_URL", MYSQL.getJdbcUrl());
        environment.put("SPRING_DATASOURCE_USERNAME", MYSQL.getUsername());
        environment.put("SPRING_DATASOURCE_PASSWORD", MYSQL.getPassword());
        if (deliveryEnabled) {
            environment.put("BATON_BRIEF_DELIVERY_ENABLED", "true");
            environment.put("BATON_BRIEF_BASE_URL", "http://127.0.0.1:" + briefPort);
            environment.put("BATON_BRIEF_BEARER_TOKEN", BRIEF_BEARER_TOKEN);
            environment.put("BATON_BRIEF_CONNECT_TIMEOUT", "PT0.2S");
            environment.put("BATON_BRIEF_READ_TIMEOUT", "PT2S");
            environment.put("BATON_BRIEF_DISPATCH_INTERVAL", "PT0.2S");
            environment.put("BATON_BRIEF_RECONCILIATION_INTERVAL", "PT0.2S");
        }
        return Map.copyOf(environment);
    }

    private static String workspacePath(UUID workspaceId, UUID seasonId) {
        return "/api/v1/teams/" + workspaceId + "/seasons/" + seasonId + "/workspace";
    }

    private static String rolesPath(UUID workspaceId, UUID seasonId) {
        return "/api/v1/teams/" + workspaceId + "/seasons/" + seasonId + "/roles";
    }

    private static String roleRequest(UUID currentMemberId) {
        String memberValue = currentMemberId == null ? "null" : "\"" + currentMemberId + "\"";
        return """
               {
                 "name": "진행자",
                 "purpose": "매주 스터디 진행을 맡습니다",
                 "currentMemberId": %s,
                 "responsibilities": ["진행 순서 확인"]
               }
               """.formatted(memberValue);
    }

    private static void clearBriefDerivedState(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.update("DELETE FROM brief_continuity_outbox");
        jdbcTemplate.update("DELETE FROM brief_continuity_signal");
    }

    private int outboxCount(JdbcTemplate jdbcTemplate) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM brief_continuity_outbox",
                Integer.class
        );
    }

    private void simulateLostResponse(
            JdbcTemplate jdbcTemplate,
            UUID signalId,
            long revision
    ) {
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
                signalId.toString(),
                revision
        );
    }

    private void awaitDelivered(
            JdbcTemplate jdbcTemplate,
            UUID signalId,
            long revision,
            String expectedResultCode
    ) {
        await("리비전 " + revision + " 전달 완료", () ->
                "DELIVERED".equals(outboxValue(
                        jdbcTemplate,
                        signalId,
                        revision,
                        "delivery_status",
                        String.class
                )) && expectedResultCode.equals(outboxValue(
                        jdbcTemplate,
                        signalId,
                        revision,
                        "result_code",
                        String.class
                ))
        );
    }

    private int outboxAttempts(JdbcTemplate jdbcTemplate, UUID signalId, long revision) {
        return outboxValue(
                jdbcTemplate,
                signalId,
                revision,
                "attempt_count",
                Integer.class
        );
    }

    private <T> T outboxValue(
            JdbcTemplate jdbcTemplate,
            UUID signalId,
            long revision,
            String column,
            Class<T> type
    ) {
        return jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM brief_continuity_outbox "
                        + "WHERE signal_id = UUID_TO_BIN(?) AND aggregate_revision = ?",
                type,
                signalId.toString(),
                revision
        );
    }

    private UUID outboxEventId(JdbcTemplate jdbcTemplate, UUID signalId, long revision) {
        return UUID.fromString(outboxValue(
                jdbcTemplate,
                signalId,
                revision,
                "BIN_TO_UUID(event_id)",
                String.class
        ));
    }

    private JsonNode getCurrentAttentionItem(
            int port,
            UUID workspaceId,
            UUID seasonId,
            String sourceReference
    ) throws Exception {
        String path = "/api/v1/workspaces/" + workspaceId
                + "/seasons/" + seasonId
                + "/attention-items/current?eventType=ROLE_UNASSIGNED&sourceReference="
                + URLEncoder.encode(sourceReference, StandardCharsets.UTF_8);
        return getJson(port, path);
    }

    private JsonNode getJson(int port, String path) throws Exception {
        return requestJson(port, "GET", path, Map.of(), null, 200);
    }

    private JsonNode requestJson(
            int port,
            String method,
            String path,
            Map<String, String> headers,
            String body,
            int expectedStatus
    ) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .timeout(REQUEST_TIMEOUT);
        headers.forEach(request::header);
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json");
            request.method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        HttpResponse<String> response = httpClient.send(
                request.build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(response.statusCode())
                .as("%s %s 응답: %s", method, path, response.body())
                .isEqualTo(expectedStatus);
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
