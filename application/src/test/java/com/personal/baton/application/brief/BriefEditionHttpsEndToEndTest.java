package com.personal.baton.application.brief;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.ContainerNetwork;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Tag("crossservice")
@Testcontainers
class BriefEditionHttpsEndToEndTest {

    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(90);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
    private static final String CURRENT_SERVICE_TOKEN =
            "brief-service-current-token-000000000001";
    private static final String PREVIOUS_SERVICE_TOKEN =
            "brief-service-previous-token-000000001";
    private static final String ACCOUNT_EMAIL = "brief-e2e@example.com";
    private static final String ACCOUNT_PASSWORD = "brief-e2e-password-2026";
    private static final String TRUSTSTORE_PASSWORD = "changeit";
    private static final DockerImageName JAVA_RUNTIME_IMAGE = DockerImageName.parse(
            "eclipse-temurin:21.0.11_10-jre-alpine-3.23"
                    + "@sha256:3f08b13888f595cc49edabea7250ba69499ba25602b267da591720769400e08c"
    );

    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer(
            "mysql@sha256:b3b90af2a6552ae30c266fdb7d5dd55f3afb72404bb78d37fe8a23eb857fd3fb"
    )
            .withDatabaseName("baton_brief_edition_cross_service")
            .withUsername("baton")
            .withPassword("password");

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            "postgres:18.6-alpine"
    ).withDatabaseName("baton_brief_edition_cross_service")
            .withUsername("brief")
            .withPassword("brief");

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @TempDir
    private Path tempDirectory;

    @DisplayName("비공개 HTTPS 환경에서 생성 재시도와 서비스 토큰 교체가 완료된다")
    @Test
    void connectsActualEditionFlowThroughPrivateHttpsBoundary() throws Exception {
        Path batonJar = requiredJar("baton.boot.jar");
        Path briefJar = requiredJar("brief.boot.jar");
        Path briefRepository = findBriefRepository(briefJar);
        TlsFiles tlsFiles = createTlsFiles();
        Path overlappingBriefConfig = secretDirectory(
                "brief-overlapping-config",
                Map.of(
                        "brief.service-api.bearer-token", CURRENT_SERVICE_TOKEN,
                        "brief.service-api.previous-bearer-token", PREVIOUS_SERVICE_TOKEN
                )
        );
        Path currentBriefConfig = secretDirectory(
                "brief-current-config",
                Map.of("brief.service-api.bearer-token", CURRENT_SERVICE_TOKEN)
        );
        Path previousBatonConfig = secretDirectory(
                "baton-previous-config",
                Map.of("baton.brief.service-api.bearer-token", PREVIOUS_SERVICE_TOKEN)
        );
        Path currentBatonConfig = secretDirectory(
                "baton-current-config",
                Map.of("baton.brief.service-api.bearer-token", CURRENT_SERVICE_TOKEN)
        );
        Path batonKeys = mountedFilesDirectory(
                "baton-keys",
                Map.of("brief-service-truststore.p12", tlsFiles.truststore())
        );
        Path caddySecrets = mountedFilesDirectory(
                "caddy-secrets",
                Map.of(
                        "brief-service-tls-cert", tlsFiles.certificate(),
                        "brief-service-tls-key", tlsFiles.privateKey()
                )
        );
        ImageFromDockerfile caddyImage = new ImageFromDockerfile()
                .withDockerfile(briefRepository.resolve("Dockerfile.service-caddy"));
        JdbcTemplate batonDatabase = new JdbcTemplate(new DriverManagerDataSource(
                MYSQL.getJdbcUrl(),
                MYSQL.getUsername(),
                MYSQL.getPassword()
        ));
        JdbcTemplate briefDatabase = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword()
        ));

        try (Network batonData = Network.newNetwork();
                Network briefData = Network.newNetwork();
                Network briefProxy = internalNetwork();
                Network serviceNetwork = internalNetwork()) {
            connect(MYSQL.getContainerId(), batonData, "mysql");
            connect(POSTGRES.getContainerId(), briefData, "postgres");
            try {
                assertInternal(briefProxy);
                assertInternal(serviceNetwork);

                Workspace firstWorkspace;
                UUID firstExecutionId;
                UUID firstEditionId;
                String firstEtag;
                try (GenericContainer<?> brief = briefContainer(
                        briefJar,
                        briefData,
                        overlappingBriefConfig
                ); GenericContainer<?> caddy = caddyContainer(
                        caddyImage,
                        briefRepository,
                        caddySecrets,
                        briefProxy
                )) {
                    startBriefAndCaddy(brief, caddy, briefProxy, serviceNetwork);
                    try (GenericContainer<?> baton = batonContainer(
                            batonJar,
                            batonData,
                            batonKeys,
                            previousBatonConfig
                    )) {
                        startBaton(baton, serviceNetwork);
                        assertRuntimeNetworks(baton, brief, caddy);
                        SessionClient session = session(baton);
                        firstWorkspace = session.createWorkspace(
                                "brief-edition-https-workspace-0001",
                                "BRIEF HTTPS 교차 서비스 팀"
                        );
                        insertAccount(batonDatabase);
                        session.attention(firstWorkspace, "/summary", 401);
                        session.attention(firstWorkspace, "/resolutions", 401);
                        session.send("GET", firstWorkspace.generationPath() + "/" + UUID.randomUUID() + "/previous-week",
                                Map.of("X-Baton-Access-Key", firstWorkspace.accessKey()), null, false, 401);
                        session.login();
                        session.attention(firstWorkspace, "/summary", 403);
                        session.attention(firstWorkspace, "/resolutions", 403);
                        session.send("GET", firstWorkspace.generationPath() + "/" + UUID.randomUUID() + "/previous-week",
                                Map.of("X-Baton-Access-Key", firstWorkspace.accessKey()), null, false, 403);
                        session.claimMembership(firstWorkspace);
                        assertThat(json(session.attention(firstWorkspace, "/summary", 200)).path("highCount").asLong()).isZero();

                        HttpResponse<String> created = session.generate(firstWorkspace, 201);
                        JsonNode createdBody = json(created);
                        firstExecutionId = UUID.fromString(
                                createdBody.path("executionId").asText()
                        );
                        firstEditionId = UUID.fromString(
                                createdBody.path("editionId").asText()
                        );
                        firstEtag = requiredHeader(created, "ETag");
                        assertThat(createdBody.path("deliveryWatermark").asLong()).isZero();
                        assertThat(createdBody.path("created").asBoolean()).isTrue();
                        assertThat(requiredHeader(created, "Location"))
                                .isEqualTo(firstWorkspace.latestPath());
                        assertThat(editionCount(
                                briefDatabase,
                                firstWorkspace.teamId(),
                                firstWorkspace.seasonId()
                        )).isOne();

                        simulateGenerationResponseLoss(batonDatabase, firstExecutionId);
                        assertThat(json(session.send("GET", firstWorkspace.generationPath() + "/" + firstEditionId + "/delivery-status",
                                Map.of("X-Baton-Access-Key", firstWorkspace.accessKey()), null, false, 200)).path("status").asText()).isEqualTo("UNKNOWN");
                        HttpResponse<String> retried = session.generate(firstWorkspace, 200);
                        JsonNode retriedBody = json(retried);
                        assertThat(retriedBody.path("executionId").asText())
                                .isEqualTo(firstExecutionId.toString());
                        assertThat(retriedBody.path("editionId").asText())
                                .isEqualTo(firstEditionId.toString());
                        assertThat(retriedBody.path("created").asBoolean()).isFalse();
                        assertThat(requiredHeader(retried, "ETag")).isEqualTo(firstEtag);
                        assertThat(executionAttempts(batonDatabase, firstExecutionId)).isEqualTo(2);
                        assertThat(editionCount(
                                briefDatabase,
                                firstWorkspace.teamId(),
                                firstWorkspace.seasonId()
                        )).isOne();

                        HttpResponse<String> latest = session.latest(
                                firstWorkspace,
                                Map.of(),
                                200
                        );
                        assertThat(json(latest).path("editionId").asText())
                                .isEqualTo(firstEditionId.toString());
                        assertThat(requiredHeader(latest, "ETag")).isEqualTo(firstEtag);
                        session.latest(
                                firstWorkspace,
                                Map.of("If-None-Match", firstEtag),
                                304
                        );

                        // 조회 계약은 별도 BRIEF DB의 대표 현재 투영으로 검증한다. 이벤트 생산 검증은 포함하지 않는다.
                        for (int index = 0; index < 3; index++) {
                            briefDatabase.update("""
                                    INSERT INTO attention_item (workspace_id, season_id, event_type, source_reference,
                                        severity, item_status, observed_at, rule_version, last_revision, revision_gap)
                                    VALUES (?, ?, 'ROLE_UNASSIGNED', ?, ?, 'ACTIVE', ?, 1, 3, ?)
                                    """, firstWorkspace.teamId(), firstWorkspace.seasonId(),
                                    List.of("role:+& 한글", "role:b", "role:c+& 한글").get(index),
                                    index < 2 ? "HIGH" : "MEDIUM",
                                    Instant.parse("2026-08-31T00:00:00Z").atOffset(ZoneOffset.UTC), index == 2);
                        }
                        JsonNode summary = json(session.attention(firstWorkspace, "/summary", 200));
                        assertThat(summary.path("highCount").asLong()).isEqualTo(2);
                        assertThat(summary.path("mediumCount").asLong()).isEqualTo(1);
                        assertThat(summary.path("revisionGapCount").asLong()).isEqualTo(1);
                        JsonNode firstPage = json(session.attention(firstWorkspace,
                                "?severity=HIGH&revisionGap=false&limit=1", 200));
                        assertThat(firstPage.path("items").get(0).path("sourceReference").asText()).isEqualTo("role:+& 한글");
                        JsonNode next = json(session.attention(firstWorkspace,
                                "?severity=HIGH&revisionGap=false&limit=1&afterEventType=ROLE_UNASSIGNED&afterSourceReference="
                                        + URLEncoder.encode(firstPage.path("nextCursor").path("sourceReference").asText(), StandardCharsets.UTF_8), 200));
                        assertThat(next.path("items").get(0).path("sourceReference").asText()).isEqualTo("role:b");
                        assertThat(next.path("nextCursor").isNull()).isTrue();
                        assertThat(json(session.attention(firstWorkspace, "?severity=HIGH&revisionGap=true", 200))
                                .path("items").isEmpty()).isTrue();
                        for (long revision : List.of(1L, 3L)) {
                            briefDatabase.update("""
                                    INSERT INTO source_event_receipt (event_id, event_type, event_version, workspace_id,
                                        season_id, source_reference, aggregate_revision, occurred_at, event_state,
                                        payload_fingerprint, processing_outcome, received_at, source_severity)
                                    VALUES (?, 'ROLE_UNASSIGNED', 2, ?, ?, 'role:c+& 한글', ?, ?, 'ACTIVE', ?, ?, ?, 'WARNING')
                                    """, UUID.randomUUID(), firstWorkspace.teamId(), firstWorkspace.seasonId(), revision,
                                    Instant.parse("2026-08-31T00:00:00Z").atOffset(ZoneOffset.UTC), "0".repeat(64),
                                    revision == 3 ? "APPLIED_WITH_GAP" : "APPLIED",
                                    Instant.parse("2026-08-31T00:00:00Z").atOffset(ZoneOffset.UTC));
                        }
                        String transitions = "/transitions?eventType=ROLE_UNASSIGNED&sourceReference=role%3Ac%2B%26%20%ED%95%9C%EA%B8%80&limit=1";
                        JsonNode transitionPage = json(session.attention(firstWorkspace, transitions, 200));
                        assertThat(transitionPage.path("transitions").get(0).path("aggregateRevision").asLong()).isEqualTo(3);
                        assertThat(transitionPage.path("transitions").get(0).path("detectedRevisionGap").asBoolean()).isTrue();
                        assertThat(transitionPage.path("transitions").get(0).path("sourceSeverity").asText()).isEqualTo("WARNING");
                        assertThat(transitionPage.path("nextBeforeAggregateRevision").asLong()).isEqualTo(3);
                        JsonNode olderTransitions = json(session.attention(firstWorkspace, transitions + "&beforeAggregateRevision=3", 200));
                        assertThat(olderTransitions.path("transitions").get(0).path("aggregateRevision").asLong()).isEqualTo(1);
                        assertThat(olderTransitions.path("transitions").get(0).path("detectedRevisionGap").asBoolean()).isFalse();
                        assertThat(olderTransitions.path("nextBeforeAggregateRevision").isNull()).isTrue();
                        var resolvedAt = Instant.parse(json(latest).path("windowStart").asText()).atOffset(ZoneOffset.UTC);
                        briefDatabase.update("""
                                INSERT INTO source_event_receipt (event_id, event_type, event_version, workspace_id,
                                    season_id, source_reference, aggregate_revision, occurred_at, event_state,
                                    payload_fingerprint, processing_outcome, received_at, source_severity)
                                VALUES (?, 'ROLE_UNASSIGNED', 2, ?, ?, 'role:c+& 한글', 4, ?, 'RESOLVED', ?, 'APPLIED', ?, 'WARNING')
                                """, UUID.randomUUID(), firstWorkspace.teamId(), firstWorkspace.seasonId(), resolvedAt, "0".repeat(64), resolvedAt);
                        briefDatabase.update("UPDATE attention_item SET item_status = 'RESOLVED', last_revision = 4 WHERE workspace_id = ? AND season_id = ? AND source_reference = 'role:c+& 한글'",
                                firstWorkspace.teamId(), firstWorkspace.seasonId());
                        JsonNode resolutions = json(session.attention(firstWorkspace, "/resolutions", 200));
                        assertThat(resolutions.path("resolvedCount").asLong()).isOne();
                        assertThat(resolutions.path("items").get(0).path("sourceReference").asText()).isEqualTo("role:c+& 한글");
                        assertThat(resolutions.path("items").get(0).path("resolvedAt").asText()).isEqualTo(resolvedAt.toInstant().toString());
                        assertThat(resolutions.path("items").get(0).path("resolvedRevision").asLong()).isEqualTo(4);
                        JsonNode resolutionEnd = json(session.attention(firstWorkspace, "/resolutions?limit=1&afterEventType=ROLE_UNASSIGNED&afterSourceReference="
                                + URLEncoder.encode("role:c+& 한글", StandardCharsets.UTF_8), 200));
                        assertThat(resolutionEnd.path("items").size()).isZero();
                        assertThat(resolutionEnd.path("resolvedCount").asLong()).isOne();
                        assertThat(resolutions.path("weekStart").asText()).isEqualTo(json(latest).path("weekStart").asText());
                        session.attention(new Workspace(firstWorkspace.teamId(), UUID.randomUUID(),
                                firstWorkspace.memberId(), firstWorkspace.accessKey()), "/summary", 404);
                        session.attention(new Workspace(firstWorkspace.teamId(), firstWorkspace.seasonId(),
                                firstWorkspace.memberId(), "wrong-access-key"), "", 403);

                        assertSecretsAbsent(baton.getLogs(), caddy.getLogs(), brief.getLogs());
                    }
                }

                try (GenericContainer<?> brief = briefContainer(
                        briefJar,
                        briefData,
                        currentBriefConfig
                ); GenericContainer<?> caddy = caddyContainer(
                        caddyImage,
                        briefRepository,
                        caddySecrets,
                        briefProxy
                )) {
                    startBriefAndCaddy(brief, caddy, briefProxy, serviceNetwork);

                    try (GenericContainer<?> batonWithPreviousToken = batonContainer(
                            batonJar,
                            batonData,
                            batonKeys,
                            previousBatonConfig
                    )) {
                        startBaton(batonWithPreviousToken, serviceNetwork);
                        SessionClient rejectedSession = session(batonWithPreviousToken);
                        rejectedSession.login();
                        HttpResponse<String> rejected = rejectedSession.latest(
                                firstWorkspace,
                                Map.of(),
                                503
                        );
                        assertThat(json(rejected).path("code").asText())
                                .isEqualTo("BRIEF_CONFIGURATION_ERROR");
                        assertThat(json(rejectedSession.attention(firstWorkspace, "/summary", 503))
                                .path("code").asText()).isEqualTo("BRIEF_CONFIGURATION_ERROR");
                        assertSecretsAbsent(
                                batonWithPreviousToken.getLogs(),
                                caddy.getLogs(),
                                brief.getLogs()
                        );
                    }

                    try (GenericContainer<?> batonWithCurrentToken = batonContainer(
                            batonJar,
                            batonData,
                            batonKeys,
                            currentBatonConfig
                    )) {
                        startBaton(batonWithCurrentToken, serviceNetwork);
                        SessionClient currentSession = session(batonWithCurrentToken);
                        currentSession.login();
                        HttpResponse<String> latest = currentSession.latest(
                                firstWorkspace,
                                Map.of(),
                                200
                        );
                        assertThat(json(latest).path("editionId").asText())
                                .isEqualTo(firstEditionId.toString());
                        assertThat(requiredHeader(latest, "ETag")).isEqualTo(firstEtag);

                        Workspace secondWorkspace = currentSession.createWorkspace(
                                "brief-edition-https-workspace-0002",
                                "BRIEF HTTPS token 교체 팀"
                        );
                        currentSession.claimMembership(secondWorkspace);
                        // 현재 주차보다 충분히 앞선 미해소 항목이 실제 생성·조회 응답까지 전달되는지 확인한다.
                        briefDatabase.update("""
                                INSERT INTO attention_item (workspace_id, season_id, event_type, source_reference,
                                    severity, item_status, observed_at, rule_version, last_revision, revision_gap)
                                VALUES (?, ?, 'ROLE_UNASSIGNED', 'role:carry-over', 'HIGH', 'ACTIVE', ?, 1, 1, false)
                                """, secondWorkspace.teamId(), secondWorkspace.seasonId(),
                                Instant.parse("2020-01-01T00:00:00Z").atOffset(ZoneOffset.UTC));
                        HttpResponse<String> secondGeneration = currentSession.generate(
                                secondWorkspace,
                                201
                        );
                        assertThat(json(secondGeneration).path("created").asBoolean()).isTrue();
                        JsonNode secondEdition = json(currentSession.latest(secondWorkspace, Map.of(), 200));
                        assertThat(secondEdition.path("ruleVersion").asInt()).isEqualTo(2);
                        assertThat(secondEdition.path("items").get(0).path("section").asText()).isEqualTo("CARRY_OVER");
                        assertThat(editionCount(
                                briefDatabase,
                                secondWorkspace.teamId(),
                                secondWorkspace.seasonId()
                        )).isOne();
                        Map<String, String> secondHeaders = Map.of("X-Baton-Access-Key", secondWorkspace.accessKey());
                        String secondId = secondEdition.path("editionId").asText();
                        String secondPath = secondWorkspace.generationPath() + "/" + secondId;
                        JsonNode history = json(currentSession.send("GET", secondWorkspace.generationPath(), secondHeaders, null, false, 200));
                        assertThat(history.path("editions").get(0).path("editionId").asText()).isEqualTo(secondId);
                        assertThat(json(currentSession.send("GET", secondPath, secondHeaders, null, false, 200)).path("editionId").asText()).isEqualTo(secondId);
                        currentSession.send("GET", firstWorkspace.generationPath() + "/" + secondId,
                                Map.of("X-Baton-Access-Key", firstWorkspace.accessKey()), null, false, 404);
                        currentSession.send("GET", firstWorkspace.generationPath() + "/" + secondId + "/delivery-status",
                                Map.of("X-Baton-Access-Key", firstWorkspace.accessKey()), null, false, 404);
                        currentSession.send("GET", secondPath + "/changes?fromEditionId=" + firstEditionId,
                                secondHeaders, null, false, 404);
                        briefDatabase.update("UPDATE attention_item SET severity = 'MEDIUM', last_revision = 2 WHERE workspace_id = ? AND season_id = ?",
                                secondWorkspace.teamId(), secondWorkspace.seasonId());
                        simulateGenerationResponseLoss(batonDatabase, UUID.fromString(json(secondGeneration).path("executionId").asText()));
                        JsonNode changedGeneration = json(currentSession.generate(secondWorkspace, 201));
                        String changedPath = secondWorkspace.generationPath() + "/" + changedGeneration.path("editionId").asText();
                        JsonNode comparison = json(currentSession.send("GET", changedPath + "/changes?fromEditionId=" + secondId,
                                secondHeaders, null, false, 200));
                        assertThat(comparison.path("changed").size()).isEqualTo(1);
                        assertThat(comparison.path("changed").get(0).path("before").path("severity").asText()).isEqualTo("HIGH");
                        assertThat(comparison.path("changed").get(0).path("after").path("severity").asText()).isEqualTo("MEDIUM");
                        JsonNode older = json(currentSession.send("GET", secondWorkspace.generationPath() + "?beforeGeneration=2&limit=1", secondHeaders, null, false, 200));
                        assertThat(older.path("editions").get(0).path("editionId").asText()).isEqualTo(secondId);
                        String contextBase = secondWorkspace.generationPath().replace("/editions", "");
                        assertThat(json(currentSession.send("GET", contextBase + "/generation-readiness", secondHeaders, null, false, 200)).path("status").asText()).isEqualTo("READY");

                        // BATON 원본 API로 만든 신호의 현재 업무 연결과 미전달 상태를 확인한다.
                        JsonNode role = json(currentSession.send("POST", "/api/v1/teams/" + secondWorkspace.teamId() + "/seasons/" + secondWorkspace.seasonId() + "/roles",
                                Map.of("X-Baton-Access-Key", secondWorkspace.accessKey(), "Content-Type", "application/json", "Idempotency-Key", UUID.randomUUID().toString()),
                                """
                                {"name":"브리프 업무 연결","purpose":"업무 이름과 이동 대상 확인","currentMemberId":null,"nextMemberId":null,
                                 "assignmentStartDate":null,"assignmentEndDate":null,"responsibilities":["주간 확인"],"risk":null}
                                """, true, 201));
                        String signalId = batonDatabase.queryForObject("SELECT BIN_TO_UUID(signal_id) FROM brief_continuity_signal WHERE team_id = UUID_TO_BIN(?) AND season_id = UUID_TO_BIN(?) AND subject_id = UUID_TO_BIN(?) AND signal_type = 'ROLE_UNASSIGNED'",
                                String.class, secondWorkspace.teamId().toString(), secondWorkspace.seasonId().toString(), role.path("id").asText());
                        JsonNode contexts = json(currentSession.send("POST", contextBase + "/sources/query",
                                Map.of("X-Baton-Access-Key", secondWorkspace.accessKey(), "Content-Type", "application/json"),
                                "{\"sources\":[{\"eventType\":\"ROLE_UNASSIGNED\",\"sourceReference\":\"baton-continuity:" + signalId + "\"}]}", true, 200));
                        assertThat(contexts.path("sources").get(0).path("target").path("title").asText()).isEqualTo("브리프 업무 연결");
                        assertThat(contexts.path("sources").get(0).path("target").path("roleId").asText()).isEqualTo(role.path("id").asText());
                        assertThat(json(currentSession.send("GET", contextBase + "/generation-readiness", secondHeaders, null, false, 200)).path("status").asText()).isEqualTo("DELIVERY_PENDING");
                        String deliveryPath = changedPath + "/delivery-status";
                        assertThat(json(currentSession.send("GET", deliveryPath, secondHeaders, null, false, 200)).path("status").asText()).isEqualTo("NO_ADDITIONAL_DELIVERIES");
                        // 추가 전달 조회용 완료 기록만 준비한다. 실제 이벤트 송신 검증은 포함하지 않는다.
                        batonDatabase.update("UPDATE brief_continuity_outbox SET delivery_status = 'DELIVERED', completed_at = occurred_at WHERE workspace_id = UUID_TO_BIN(?) AND season_id = UUID_TO_BIN(?)",
                                secondWorkspace.teamId().toString(), secondWorkspace.seasonId().toString());
                        assertThat(json(currentSession.send("GET", deliveryPath, secondHeaders, null, false, 200)).path("status").asText()).isEqualTo("ADDITIONAL_DELIVERIES");
                        JsonNode reused = json(currentSession.generate(secondWorkspace, 200));
                        assertThat(reused.path("editionId").asText()).isEqualTo(changedGeneration.path("editionId").asText());
                        assertThat(reused.path("created").asBoolean()).isFalse();
                        assertThat(json(currentSession.send("GET", deliveryPath, secondHeaders, null, false, 200)).path("status").asText()).isEqualTo("NO_ADDITIONAL_DELIVERIES");
                        currentSession.send("GET", changedPath + "/previous-week", secondHeaders, null, false, 404);
                        currentSession.send("GET", firstWorkspace.generationPath() + "/" + secondId + "/previous-week",
                                Map.of("X-Baton-Access-Key", firstWorkspace.accessKey()), null, false, 404);
                        // 지난주 저장 자료를 준비해 정확한 시간대·최대 생성 순번 선택을 실제 내부 HTTPS에서 확인한다.
                        var previousWeek = LocalDate.parse(secondEdition.path("weekStart").asText()).minusWeeks(1);
                        UUID previousEditionId = UUID.randomUUID();
                        for (int index = 3; index <= 5; index++) {
                            var zone = ZoneId.of(index == 5 ? "America/New_York" : secondEdition.path("zoneId").asText());
                            briefDatabase.update("""
                                    INSERT INTO brief_edition (edition_id, workspace_id, season_id, generation, week_start, zone_id,
                                        window_start, window_end, rule_version, source_cursor, state_fingerprint, generated_at)
                                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, 0, ?, ?)
                                    """, index == 4 ? previousEditionId : UUID.randomUUID(), secondWorkspace.teamId(), secondWorkspace.seasonId(),
                                    index, previousWeek, zone.getId(), previousWeek.atStartOfDay(zone).toOffsetDateTime(),
                                    previousWeek.plusWeeks(1).atStartOfDay(zone).toOffsetDateTime(), Integer.toString(index).repeat(64),
                                    Instant.now().atOffset(ZoneOffset.UTC));
                        }
                        var previousResponse = currentSession.send("GET", changedPath + "/previous-week", secondHeaders, null, false, 200);
                        assertThat(json(previousResponse).path("editionId").asText()).isEqualTo(previousEditionId.toString());
                        assertThat(json(previousResponse).path("ruleVersion").asInt()).isEqualTo(1);
                        assertThat(requiredHeader(previousResponse, "ETag")).contains(previousEditionId.toString());
                        assertSecretsAbsent(
                                batonWithCurrentToken.getLogs(),
                                caddy.getLogs(),
                                brief.getLogs()
                        );
                    }
                }
            } finally {
                disconnect(MYSQL.getContainerId(), batonData);
                disconnect(POSTGRES.getContainerId(), briefData);
            }
        }
    }

    private GenericContainer<?> briefContainer(
            Path jar,
            Network dataNetwork,
            Path configDirectory
    ) {
        return securedJavaContainer(jar)
                .withNetwork(dataNetwork)
                .withNetworkAliases("brief-data-app")
                .withEnv("SERVER_ADDRESS", "0.0.0.0")
                .withEnv(
                        "SPRING_DATASOURCE_URL",
                        "jdbc:postgresql://postgres:5432/" + POSTGRES.getDatabaseName()
                )
                .withEnv("SPRING_DATASOURCE_USERNAME", POSTGRES.getUsername())
                .withEnv("SPRING_DATASOURCE_PASSWORD", POSTGRES.getPassword())
                .withEnv("SPRING_CONFIG_IMPORT", "configtree:/run/secrets/")
                .withEnv("BRIEF_SERVICE_API_AUTHENTICATION_REQUIRED", "true")
                .withFileSystemBind(
                        configDirectory.toAbsolutePath().toString(),
                        "/run/secrets",
                        BindMode.READ_ONLY
                )
                .waitingFor(Wait.forLogMessage(".*Started BriefApplication.*\\n", 1)
                        .withStartupTimeout(STARTUP_TIMEOUT));
    }

    private GenericContainer<?> caddyContainer(
            ImageFromDockerfile image,
            Path briefRepository,
            Path secretDirectory,
            Network proxyNetwork
    ) {
        return new GenericContainer<>(image)
                .withNetwork(proxyNetwork)
                .withNetworkAliases("brief-service-caddy")
                .withEnv("BRIEF_SERVICE_HOST", "brief-service")
                .withFileSystemBind(
                        briefRepository.resolve("ops/Caddyfile.service").toString(),
                        "/etc/caddy/Caddyfile",
                        BindMode.READ_ONLY
                )
                .withFileSystemBind(
                        secretDirectory.toAbsolutePath().toString(),
                        "/run/secrets",
                        BindMode.READ_ONLY
                )
                .withTmpFs(Map.of(
                        "/tmp", "rw,noexec,nosuid,nodev,size=16m,mode=1777",
                        "/data", "rw,noexec,nosuid,nodev,size=16m,mode=1777",
                        "/config", "rw,noexec,nosuid,nodev,size=16m,mode=1777"
                ))
                .withCreateContainerCmdModifier(command -> command.getHostConfig()
                        .withReadonlyRootfs(true)
                        .withCapDrop(Capability.ALL)
                        .withSecurityOpts(List.of("no-new-privileges:true")));
    }

    private GenericContainer<?> batonContainer(
            Path jar,
            Network dataNetwork,
            Path keyDirectory,
            Path configDirectory
    ) {
        return securedJavaContainer(jar)
                .withNetwork(dataNetwork)
                .withNetworkAliases("baton-data-app")
                .withExposedPorts(8080)
                .withEnv("BATON_SERVER_PORT", "8080")
                .withEnv(
                        "SPRING_DATASOURCE_URL",
                        "jdbc:mysql://mysql:3306/" + MYSQL.getDatabaseName()
                                + "?useSSL=false&allowPublicKeyRetrieval=true"
                                + "&serverTimezone=UTC&characterEncoding=UTF-8"
                )
                .withEnv("SPRING_DATASOURCE_USERNAME", MYSQL.getUsername())
                .withEnv("SPRING_DATASOURCE_PASSWORD", MYSQL.getPassword())
                .withEnv("SPRING_CONFIG_IMPORT", "configtree:/run/baton-config/")
                .withEnv("BATON_BRIEF_SERVICE_API_ENABLED", "true")
                .withEnv(
                        "BATON_BRIEF_SERVICE_API_BASE_URL",
                        "https://brief-service:8443"
                )
                .withEnv("BATON_BRIEF_SERVICE_API_CONNECT_TIMEOUT", "PT1S")
                .withEnv("BATON_BRIEF_SERVICE_API_READ_TIMEOUT", "PT3S")
                .withEnv(
                        "JAVA_TOOL_OPTIONS",
                        "-Djavax.net.ssl.trustStore=/run/baton-keys/brief-service-truststore.p12 "
                                + "-Djavax.net.ssl.trustStoreType=PKCS12 "
                                + "-Djavax.net.ssl.trustStorePassword=" + TRUSTSTORE_PASSWORD
                                + " -Djava.io.tmpdir=/tmp"
                )
                .withFileSystemBind(
                        configDirectory.toAbsolutePath().toString(),
                        "/run/baton-config",
                        BindMode.READ_ONLY
                )
                .withFileSystemBind(
                        keyDirectory.toAbsolutePath().toString(),
                        "/run/baton-keys",
                        BindMode.READ_ONLY
                )
                .waitingFor(Wait.forHttp("/actuator/health")
                        .forStatusCode(200)
                        .withStartupTimeout(STARTUP_TIMEOUT));
    }

    private GenericContainer<?> securedJavaContainer(Path jar) {
        return new GenericContainer<>(JAVA_RUNTIME_IMAGE)
                .withFileSystemBind(
                        jar.toAbsolutePath().toString(),
                        "/app/service.jar",
                        BindMode.READ_ONLY
                )
                .withWorkingDirectory("/app")
                .withCommand(
                        "java",
                        "-XX:MaxRAMPercentage=75.0",
                        "-jar",
                        "/app/service.jar"
                )
                .withTmpFs(Map.of("/tmp", "rw,noexec,nosuid,nodev,size=64m,mode=1777"))
                .withCreateContainerCmdModifier(command -> {
                    command.withUser("10001:10001");
                    command.getHostConfig()
                            .withReadonlyRootfs(true)
                            .withCapDrop(Capability.ALL)
                            .withSecurityOpts(List.of("no-new-privileges:true"));
                });
    }

    private void startBriefAndCaddy(
            GenericContainer<?> brief,
            GenericContainer<?> caddy,
            Network proxyNetwork,
            Network serviceNetwork
    ) throws IOException, InterruptedException {
        brief.start();
        connect(brief.getContainerId(), proxyNetwork, "brief");
        caddy.start();
        connect(caddy.getContainerId(), serviceNetwork, "brief-service");
        assertThat(caddy.execInContainer(
                "caddy",
                "validate",
                "--config",
                "/etc/caddy/Caddyfile",
                "--adapter",
                "caddyfile"
        ).getExitCode()).isZero();
        assertThat(caddy.execInContainer("id", "-u").getStdout().trim())
                .isEqualTo("10001");
        assertThat(caddy.execInContainer("getcap", "/usr/bin/caddy").getStdout().trim())
                .isEmpty();
        assertThat(caddy.getPortBindings()).isEmpty();
    }

    private static void startBaton(
            GenericContainer<?> baton,
            Network serviceNetwork
    ) {
        baton.start();
        connect(baton.getContainerId(), serviceNetwork, "baton-app");
    }

    private SessionClient session(GenericContainer<?> baton) {
        return new SessionClient(URI.create(
                "http://" + baton.getHost() + ":" + baton.getMappedPort(8080)
        ));
    }

    private void assertRuntimeNetworks(
            GenericContainer<?> baton,
            GenericContainer<?> brief,
            GenericContainer<?> caddy
    ) {
        assertThat(inspectNetworks(baton)).hasSize(2);
        assertThat(inspectNetworks(brief)).hasSize(2);
        assertThat(inspectNetworks(caddy)).hasSize(2);
        var hostConfig = DockerClientFactory.instance().client()
                .inspectContainerCmd(caddy.getContainerId())
                .exec()
                .getHostConfig();
        assertThat(hostConfig.getReadonlyRootfs()).isTrue();
        assertThat(hostConfig.getCapDrop()).containsExactly(Capability.ALL);
        assertThat(hostConfig.getCapAdd()).isNullOrEmpty();
        assertThat(hostConfig.getSecurityOpts()).contains("no-new-privileges:true");
    }

    private static Map<String, ContainerNetwork> inspectNetworks(GenericContainer<?> container) {
        return DockerClientFactory.instance().client()
                .inspectContainerCmd(container.getContainerId())
                .exec()
                .getNetworkSettings()
                .getNetworks();
    }

    private void insertAccount(JdbcTemplate jdbcTemplate) {
        UUID accountId = UUID.randomUUID();
        UUID identityId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        String passwordHash = "{bcrypt}" + new BCryptPasswordEncoder(4)
                .encode(ACCOUNT_PASSWORD);
        jdbcTemplate.update(
                "INSERT INTO accounts "
                        + "(id, display_name, created_at, updated_at, version) "
                        + "VALUES (UUID_TO_BIN(?), ?, ?, ?, 0)",
                accountId.toString(),
                "BRIEF 교차 서비스 사용자",
                now,
                now
        );
        jdbcTemplate.update(
                """
                INSERT INTO account_identities (
                    id,
                    account_id,
                    provider,
                    provider_subject,
                    email_snapshot,
                    email_verified,
                    created_at,
                    last_authenticated_at,
                    version
                ) VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), 'LOCAL_EMAIL', ?, ?, TRUE, ?, NULL, 0)
                """,
                identityId.toString(),
                accountId.toString(),
                ACCOUNT_EMAIL,
                ACCOUNT_EMAIL,
                now
        );
        jdbcTemplate.update(
                """
                INSERT INTO local_credentials (
                    identity_id,
                    password_hash,
                    created_at,
                    updated_at,
                    version
                ) VALUES (UUID_TO_BIN(?), ?, ?, ?, 0)
                """,
                identityId.toString(),
                passwordHash,
                now,
                now
        );
    }

    private static void simulateGenerationResponseLoss(
            JdbcTemplate jdbcTemplate,
            UUID executionId
    ) {
        assertThat(jdbcTemplate.update(
                """
                UPDATE brief_edition_generation_execution
                SET execution_status = 'RETRYABLE_FAILURE',
                    lease_token = NULL,
                    lease_expires_at = NULL,
                    result_code = 'BRIEF_NETWORK_FAILURE',
                    updated_at = ?
                WHERE execution_id = UUID_TO_BIN(?)
                AND execution_status = 'SUCCEEDED'
                """,
                LocalDateTime.now(ZoneOffset.UTC),
                executionId.toString()
        )).isOne();
    }

    private static int executionAttempts(JdbcTemplate jdbcTemplate, UUID executionId) {
        return jdbcTemplate.queryForObject(
                "SELECT attempt_count FROM brief_edition_generation_execution "
                        + "WHERE execution_id = UUID_TO_BIN(?)",
                Integer.class,
                executionId.toString()
        );
    }

    private static int editionCount(
            JdbcTemplate jdbcTemplate,
            UUID workspaceId,
            UUID seasonId
    ) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM brief_edition WHERE workspace_id = ? AND season_id = ?",
                Integer.class,
                workspaceId,
                seasonId
        );
    }

    private TlsFiles createTlsFiles() throws IOException, InterruptedException {
        Path certificate = tempDirectory.resolve("brief-service.crt");
        Path privateKey = tempDirectory.resolve("brief-service.key");
        Path truststore = tempDirectory.resolve("brief-service-truststore.p12");
        run(
                "openssl",
                "req",
                "-x509",
                "-newkey",
                "rsa:2048",
                "-nodes",
                "-keyout",
                privateKey.toString(),
                "-out",
                certificate.toString(),
                "-days",
                "1",
                "-subj",
                "/CN=brief-service",
                "-addext",
                "subjectAltName=DNS:brief-service"
        );
        run(
                Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-importcert",
                "-noprompt",
                "-storetype",
                "PKCS12",
                "-keystore",
                truststore.toString(),
                "-storepass",
                TRUSTSTORE_PASSWORD,
                "-alias",
                "brief-service",
                "-file",
                certificate.toString()
        );
        return new TlsFiles(certificate, privateKey, truststore);
    }

    private Path secretDirectory(String name, Map<String, String> values) throws IOException {
        Path directory = Files.createDirectory(tempDirectory.resolve(name));
        for (Map.Entry<String, String> entry : values.entrySet()) {
            Path file = Files.writeString(
                    directory.resolve(entry.getKey()),
                    entry.getValue(),
                    StandardCharsets.US_ASCII
            );
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r--r--r--"));
        }
        return directory;
    }

    private Path mountedFilesDirectory(String name, Map<String, Path> files) throws IOException {
        Path directory = Files.createDirectory(tempDirectory.resolve(name));
        for (Map.Entry<String, Path> entry : files.entrySet()) {
            Path file = Files.copy(entry.getValue(), directory.resolve(entry.getKey()));
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r--r--r--"));
        }
        return directory;
    }

    private void run(String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command)
                .directory(tempDirectory.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor())
                .as("명령 실행 결과: %s", output)
                .isZero();
    }

    private static Network internalNetwork() {
        return Network.builder()
                .createNetworkCmdModifier(command -> command.withInternal(true))
                .build();
    }

    private static void assertInternal(Network network) {
        assertThat(DockerClientFactory.instance().client()
                .inspectNetworkCmd()
                .withNetworkId(network.getId())
                .exec()
                .getInternal()).isTrue();
    }

    private static void connect(String containerId, Network network, String... aliases) {
        DockerClientFactory.instance().client()
                .connectToNetworkCmd()
                .withContainerId(containerId)
                .withNetworkId(network.getId())
                .withContainerNetwork(new ContainerNetwork().withAliases(aliases))
                .exec();
    }

    private static void disconnect(String containerId, Network network) {
        DockerClientFactory.instance().client()
                .disconnectFromNetworkCmd()
                .withContainerId(containerId)
                .withNetworkId(network.getId())
                .withForce(true)
                .exec();
    }

    private static Path requiredJar(String propertyName) {
        String configuredPath = System.getProperty(propertyName);
        assertThat(configuredPath).as(propertyName + " 실행 JAR 경로").isNotBlank();
        Path jar = Path.of(configuredPath).toAbsolutePath().normalize();
        assertThat(jar).as(propertyName + " 실행 JAR").isRegularFile();
        return jar;
    }

    private static Path findBriefRepository(Path briefJar) {
        Path candidate = briefJar.getParent();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("Dockerfile.service-caddy"))
                    && Files.isRegularFile(candidate.resolve("ops/Caddyfile.service"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalArgumentException(
                "BRIEF 실행 JAR 경로에서 서비스 Caddy 구성을 찾을 수 없습니다: " + briefJar
        );
    }

    private JsonNode json(HttpResponse<String> response) throws IOException {
        return jsonMapper.readTree(response.body());
    }

    private static String requiredHeader(HttpResponse<String> response, String name) {
        return response.headers().firstValue(name)
                .orElseThrow(() -> new AssertionError(name + " 응답 헤더가 없습니다"));
    }

    private static void assertSecretsAbsent(String... logs) {
        for (String log : logs) {
            assertThat(log.contains(CURRENT_SERVICE_TOKEN))
                    .as("현재 서비스 token 로그 비노출")
                    .isFalse();
            assertThat(log.contains(PREVIOUS_SERVICE_TOKEN))
                    .as("직전 서비스 token 로그 비노출")
                    .isFalse();
        }
    }

    private record TlsFiles(Path certificate, Path privateKey, Path truststore) {
    }

    private record Workspace(UUID teamId, UUID seasonId, UUID memberId, String accessKey) {

        private String generationPath() {
            return "/api/v1/teams/" + teamId + "/seasons/" + seasonId
                    + "/brief/editions";
        }

        private String latestPath() {
            return generationPath() + "/latest";
        }
    }

    private final class SessionClient {

        private final URI origin;
        private final HttpClient client;
        private String csrfHeaderName;
        private String csrfToken;
        private String accountId;

        private SessionClient(URI origin) {
            this.origin = origin;
            CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
            this.client = HttpClient.newBuilder()
                    .connectTimeout(REQUEST_TIMEOUT)
                    .cookieHandler(cookies)
                    .build();
        }

        private Workspace createWorkspace(String idempotencyKey, String teamName)
                throws Exception {
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            HttpResponse<String> response = send(
                    "POST",
                    "/api/v1/workspaces",
                    Map.of(
                            "Content-Type", "application/json",
                            "Idempotency-Key", idempotencyKey
                    ),
                    """
                    {
                      "teamName": "%s",
                      "seasonName": "BRIEF HTTPS 교차 서비스 시즌",
                      "startDate": "%s",
                      "endDate": "%s",
                      "memberNames": ["김준호"]
                    }
                    """.formatted(teamName, today.plusDays(30), today.plusDays(90)),
                    false,
                    201
            );
            JsonNode body = json(response);
            UUID teamId = UUID.fromString(body.path("teamId").asText());
            UUID seasonId = UUID.fromString(body.path("seasonId").asText());
            String accessKey = body.path("accessKey").asText();
            JsonNode workspace = json(send(
                    "GET",
                    "/api/v1/teams/" + teamId + "/seasons/" + seasonId + "/workspace",
                    Map.of("X-Baton-Access-Key", accessKey),
                    null,
                    false,
                    200
            ));
            return new Workspace(
                    teamId,
                    seasonId,
                    UUID.fromString(workspace.path("members").get(0).path("id").asText()),
                    accessKey
            );
        }

        private void login() throws Exception {
            HttpResponse<String> csrf = send(
                    "GET",
                    "/api/v1/auth/csrf",
                    Map.of(),
                    null,
                    false,
                    200
            );
            updateCsrf(json(csrf));
            send(
                    "POST",
                    "/api/v1/auth/local/session",
                    Map.of("Content-Type", "application/x-www-form-urlencoded"),
                    "email=" + encode(ACCOUNT_EMAIL) + "&password=" + encode(ACCOUNT_PASSWORD),
                    true,
                    204
            );
            HttpResponse<String> session = send(
                    "GET",
                    "/api/v1/auth/session",
                    Map.of(),
                    null,
                    false,
                    200
            );
            JsonNode sessionBody = json(session);
            assertThat(sessionBody.path("authenticated").asBoolean()).isTrue();
            accountId = sessionBody.path("accountId").asText();
            updateCsrf(sessionBody);
        }

        private void claimMembership(Workspace workspace) throws Exception {
            send(
                    "POST",
                    "/api/v1/account-membership-claims",
                    Map.of(
                            "Content-Type", "application/json",
                            "X-Baton-Access-Key", workspace.accessKey()
                    ),
                    """
                    {
                      "expectedAccountId": "%s",
                      "teamId": "%s",
                      "seasonId": "%s",
                      "memberId": "%s"
                    }
                    """.formatted(
                            accountId,
                            workspace.teamId(),
                            workspace.seasonId(),
                            workspace.memberId()
                    ),
                    true,
                    200
            );
        }

        private HttpResponse<String> generate(Workspace workspace, int expectedStatus)
                throws Exception {
            return send(
                    "POST",
                    workspace.generationPath(),
                    Map.of("X-Baton-Access-Key", workspace.accessKey()),
                    null,
                    true,
                    expectedStatus
            );
        }

        private HttpResponse<String> attention(Workspace workspace, String suffix, int status) throws Exception {
            return send("GET", "/api/v1/teams/" + workspace.teamId() + "/seasons/" + workspace.seasonId()
                            + "/brief/attention-items" + suffix,
                    Map.of("X-Baton-Access-Key", workspace.accessKey()), null, false, status);
        }

        private HttpResponse<String> latest(
                Workspace workspace,
                Map<String, String> additionalHeaders,
                int expectedStatus
        ) throws Exception {
            Map<String, String> headers = new java.util.HashMap<>(additionalHeaders);
            headers.put("X-Baton-Access-Key", workspace.accessKey());
            return send(
                    "GET",
                    workspace.latestPath(),
                    Map.copyOf(headers),
                    null,
                    false,
                    expectedStatus
            );
        }

        private HttpResponse<String> send(
                String method,
                String path,
                Map<String, String> headers,
                String body,
                boolean sameOriginMutation,
                int expectedStatus
        ) throws Exception {
            HttpRequest.Builder request = HttpRequest.newBuilder()
                    .uri(origin.resolve(path))
                    .timeout(REQUEST_TIMEOUT);
            headers.forEach(request::header);
            if (sameOriginMutation) {
                request.header("Origin", origin.toString());
                request.header("Sec-Fetch-Site", "same-origin");
                request.header(csrfHeaderName, csrfToken);
            }
            if (body == null) {
                request.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                request.method(method, HttpRequest.BodyPublishers.ofString(body));
            }
            HttpResponse<String> response = client.send(
                    request.build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertThat(response.statusCode())
                    .as("%s %s 응답: %s", method, path, response.body())
                    .isEqualTo(expectedStatus);
            return response;
        }

        private void updateCsrf(JsonNode response) {
            csrfHeaderName = response.path("csrfHeaderName").asText();
            csrfToken = response.path("csrfToken").asText();
            assertThat(csrfHeaderName).isNotBlank();
            assertThat(csrfToken).isNotBlank();
        }

        private String encode(String value) {
            return URLEncoder.encode(value, StandardCharsets.UTF_8);
        }
    }
}
