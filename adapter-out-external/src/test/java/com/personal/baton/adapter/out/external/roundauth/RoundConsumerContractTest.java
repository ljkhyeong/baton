package com.personal.baton.adapter.out.external.roundauth;

import static org.assertj.core.api.Assertions.assertThat;

import com.personal.baton.application.roundauth.port.out.ParticipationGrantSigner.ParticipationGrantClaims;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("crossservice")
class RoundConsumerContractTest {

    private static final String ISSUER = "https://baton.contract.test";
    private static final String WRONG_ISSUER = "https://other-issuer.contract.test";
    private static final String AUDIENCE = "round";
    private static final String WRONG_AUDIENCE = "not-round";
    private static final String COOKIE_NAME = "__Secure-round_access";
    private static final String ALLOWED_ORIGIN = "http://localhost:5173";
    private static final String ROOM_ID = "abcd-efgh-jkmp";
    private static final String OTHER_ROOM_ID = "qrst-uvwx-yz23";
    private static final String KEY_ID = "baton-round-contract-2026-08";
    private static final String UNKNOWN_KEY_ID = "unknown-baton-key";
    private static final UUID ACCOUNT_ID =
            UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID TEAM_ID =
            UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
    private static final Clock CLOCK = Clock.systemUTC();

    @TempDir
    private Path tempDirectory;

    @Test
    @DisplayName("BATON이 발급한 RS256 참여권을 ROUND의 TURN과 WebSocket 경계에서 검증한다")
    void verifiesBatonGrantAtRoundRuntimeBoundaries() throws Exception {
        Path signalingJar = requiredSignalingJar();
        KeyPair keyPair = generateRsaKeyPair();
        Path privateKey = writePem("round-private.pem", "PRIVATE KEY", keyPair.getPrivate().getEncoded());
        Path publicKey = writePem("round-public.pem", "PUBLIC KEY", keyPair.getPublic().getEncoded());
        NimbusParticipationGrantInfrastructure issuer = infrastructure(
                ISSUER, AUDIENCE, KEY_ID, privateKey, publicKey
        );
        NimbusParticipationGrantInfrastructure wrongAudienceIssuer = infrastructure(
                ISSUER, WRONG_AUDIENCE, KEY_ID, privateKey, publicKey
        );
        NimbusParticipationGrantInfrastructure wrongIssuer = infrastructure(
                WRONG_ISSUER, AUDIENCE, KEY_ID, privateKey, publicKey
        );
        NimbusParticipationGrantInfrastructure unknownKidIssuer = infrastructure(
                ISSUER, AUDIENCE, UNKNOWN_KEY_ID, privateKey, publicKey
        );

        try (RoundRuntime runtime = RoundRuntime.start(
                signalingJar,
                issuer.readPublicJwkSetJson(),
                tempDirectory.resolve("round-runtime")
        )) {
            Instant issuedAt = CLOCK.instant().truncatedTo(ChronoUnit.SECONDS);
            String validToken = sign(issuer, ROOM_ID, issuedAt);
            String wrongAudienceToken = sign(wrongAudienceIssuer, ROOM_ID, issuedAt);
            String wrongIssuerToken = sign(wrongIssuer, ROOM_ID, issuedAt);
            String unknownKidToken = sign(unknownKidIssuer, ROOM_ID, issuedAt);
            String expiredToken = sign(issuer, ROOM_ID, issuedAt.minusSeconds(301));

            HttpResponse<String> validTurn = runtime.requestTurnCredentials(ROOM_ID, validToken);
            HttpResponse<String> otherRoomTurn = runtime.requestTurnCredentials(OTHER_ROOM_ID, validToken);
            HttpResponse<String> wrongAudienceTurn = runtime.requestTurnCredentials(ROOM_ID, wrongAudienceToken);
            HttpResponse<String> expiredTurn = runtime.requestTurnCredentials(ROOM_ID, expiredToken);
            HttpResponse<String> wrongIssuerTurn = runtime.requestTurnCredentials(ROOM_ID, wrongIssuerToken);
            HttpResponse<String> unknownKidTurn = runtime.requestTurnCredentials(ROOM_ID, unknownKidToken);

            assertStatus(runtime, "올바른 room의 TURN 요청", validTurn, 204);
            assertStatus(runtime, "다른 room의 TURN 요청", otherRoomTurn, 403);
            assertStatus(runtime, "잘못된 audience 참여권의 TURN 요청", wrongAudienceTurn, 401);
            assertStatus(runtime, "만료된 참여권의 TURN 요청", expiredTurn, 401);
            assertStatus(runtime, "잘못된 issuer 참여권의 TURN 요청", wrongIssuerTurn, 401);
            assertStatus(runtime, "공개 JWK에 없는 kid 참여권의 TURN 요청", unknownKidTurn, 401);

            WebSocket socket = runtime.openWebSocket(ROOM_ID, validToken);
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "contract verified")
                    .get(REQUEST_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            assertThat(runtime.rejectedWebSocketStatus(OTHER_ROOM_ID, validToken))
                    .as("다른 room 경로에서 재사용한 참여권의 WebSocket handshake 상태")
                    .isEqualTo(403);
        }
    }

    private static void assertStatus(
            RoundRuntime runtime,
            String description,
            HttpResponse<String> response,
            int expectedStatus
    ) {
        assertThat(response.statusCode())
                .withFailMessage(runtime.failureMessage(description, response))
                .isEqualTo(expectedStatus);
    }

    private NimbusParticipationGrantInfrastructure infrastructure(
            String issuer,
            String audience,
            String keyId,
            Path privateKey,
            Path publicKey
    ) {
        return new NimbusParticipationGrantInfrastructure(
                issuer,
                audience,
                new NimbusParticipationGrantInfrastructure.SigningKeyMaterial(
                        keyId,
                        privateKey,
                        publicKey
                ),
                List.of()
        );
    }

    private String sign(
            NimbusParticipationGrantInfrastructure issuer,
            String roomId,
            Instant issuedAt
    ) {
        return issuer.sign(new ParticipationGrantClaims(
                ACCOUNT_ID,
                TEAM_ID,
                roomId,
                UUID.randomUUID(),
                "participant",
                issuedAt,
                issuedAt.plusSeconds(300)
        ));
    }

    private Path requiredSignalingJar() {
        String configuredPath = System.getProperty("round.signaling.jar");
        assertThat(configuredPath)
                .as("roundConsumerContractTest가 전달한 ROUND signaling bootJar 경로")
                .isNotBlank();
        Path jar = Path.of(configuredPath).toAbsolutePath().normalize();
        assertThat(jar).as("실행할 ROUND signaling bootJar").isRegularFile();
        return jar;
    }

    private static KeyPair generateRsaKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2_048);
        return generator.generateKeyPair();
    }

    private Path writePem(String fileName, String type, byte[] encoded) throws IOException {
        String content = "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(encoded)
                + "\n-----END " + type + "-----\n";
        Path path = tempDirectory.resolve(fileName);
        Files.writeString(path, content, StandardCharsets.US_ASCII);
        return path;
    }

    private static final class RoundRuntime implements AutoCloseable {

        private static final Pattern TOMCAT_PORT = Pattern.compile("Tomcat started on port (\\d+)");
        private static final int MAX_LOG_CHARACTERS = 100_000;

        private final HttpServer jwkServer;
        private final Process process;
        private final Thread processShutdownHook;
        private final Thread logReader;
        private final StringBuffer logs;
        private final HttpClient httpClient;
        private final int port;

        private RoundRuntime(
                HttpServer jwkServer,
                Process process,
                Thread processShutdownHook,
                Thread logReader,
                StringBuffer logs,
                int port
        ) {
            this.jwkServer = jwkServer;
            this.process = process;
            this.processShutdownHook = processShutdownHook;
            this.logReader = logReader;
            this.logs = logs;
            this.port = port;
            this.httpClient = HttpClient.newBuilder()
                    .connectTimeout(REQUEST_TIMEOUT)
                    .build();
        }

        static RoundRuntime start(
                Path signalingJar,
                String publicJwkSetJson,
                Path runtimeDirectory
        ) throws Exception {
            HttpServer jwkServer = startJwkServer(publicJwkSetJson);
            Process process = null;
            Thread processShutdownHook = null;
            Thread logReader = null;
            StringBuffer logs = new StringBuffer();
            try {
                ProcessBuilder processBuilder = new ProcessBuilder(
                        javaExecutable().toString(),
                        "-jar",
                        signalingJar.toString(),
                        "--server.address=127.0.0.1",
                        "--server.port=0"
                );
                Files.createDirectories(runtimeDirectory);
                processBuilder.directory(runtimeDirectory.toFile());
                processBuilder.redirectErrorStream(true);
                configureEnvironment(processBuilder.environment(), jwkServer);
                process = processBuilder.start();

                Process startedProcess = process;
                processShutdownHook = Thread.ofPlatform()
                        .name("round-contract-process-shutdown")
                        .unstarted(() -> stopProcess(startedProcess));
                Runtime.getRuntime().addShutdownHook(processShutdownHook);

                CompletableFuture<Integer> port = new CompletableFuture<>();
                logReader = Thread.ofVirtual()
                        .name("round-contract-log-reader")
                        .start(() -> readLogs(startedProcess, logs, port));
                int startedPort;
                try {
                    startedPort = port.get(STARTUP_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                } catch (Exception exception) {
                    throw new IllegalStateException(
                            "ROUND signaling이 제한 시간 안에 시작되지 않았습니다.\n" + logs,
                            exception
                    );
                }
                return new RoundRuntime(
                        jwkServer,
                        process,
                        processShutdownHook,
                        logReader,
                        logs,
                        startedPort
                );
            } catch (Exception exception) {
                removeShutdownHook(processShutdownHook);
                stopProcess(process);
                if (logReader != null) {
                    logReader.interrupt();
                }
                jwkServer.stop(0);
                throw exception;
            }
        }

        HttpResponse<String> requestTurnCredentials(String roomId, String token) throws Exception {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(httpUri("/api/rooms/" + roomId + "/turn-credentials"))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Origin", httpOrigin())
                    .header("Sec-Fetch-Site", "same-origin")
                    .header("Cookie", COOKIE_NAME + "=" + token)
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        }

        WebSocket openWebSocket(String roomId, String token) throws Exception {
            try {
                return webSocketHandshake(roomId, token)
                        .get(REQUEST_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            } catch (Exception exception) {
                throw new AssertionError(
                        "올바른 room 참여권의 WebSocket handshake가 실패했습니다.\n"
                                + processLogs(),
                        exception
                );
            }
        }

        int rejectedWebSocketStatus(String roomId, String token) throws Exception {
            try {
                WebSocket socket = webSocketHandshake(roomId, token)
                        .get(REQUEST_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                socket.abort();
                throw new AssertionError(
                        "거부되어야 할 WebSocket handshake가 수락되었습니다.\n" + processLogs()
                );
            } catch (ExecutionException exception) {
                if (exception.getCause() instanceof WebSocketHandshakeException handshake) {
                    return handshake.getResponse().statusCode();
                }
                throw new AssertionError(
                        "WebSocket handshake가 HTTP 거부 응답 없이 실패했습니다.\n"
                                + processLogs(),
                        exception
                );
            }
        }

        private CompletableFuture<WebSocket> webSocketHandshake(String roomId, String token) {
            return httpClient.newWebSocketBuilder()
                    .connectTimeout(REQUEST_TIMEOUT)
                    .header("Origin", ALLOWED_ORIGIN)
                    .header("Cookie", COOKIE_NAME + "=" + token)
                    .buildAsync(
                            webSocketUri("/rooms/" + roomId + "/signal"),
                            new WebSocket.Listener() {
                            }
                    );
        }

        String failureMessage(String description, HttpResponse<String> response) {
            return description + " 상태가 예상과 다릅니다. status="
                    + response.statusCode() + ", body=" + response.body() + "\n"
                    + processLogs();
        }

        private URI httpUri(String path) {
            return URI.create(httpOrigin() + path);
        }

        private String httpOrigin() {
            return "http://127.0.0.1:" + port;
        }

        private URI webSocketUri(String path) {
            return URI.create("ws://127.0.0.1:" + port + path);
        }

        private String processLogs() {
            return "ROUND process logs:\n" + logs;
        }

        private static HttpServer startJwkServer(String publicJwkSetJson) throws IOException {
            byte[] body = publicJwkSetJson.getBytes(StandardCharsets.UTF_8);
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/jwks", exchange -> respondWithJwkSet(exchange, body));
            server.start();
            return server;
        }

        private static void respondWithJwkSet(HttpExchange exchange, byte[] body)
                throws IOException {
            try (exchange) {
                exchange.getResponseHeaders().set("Content-Type", "application/jwk-set+json");
                exchange.getResponseHeaders().set("Cache-Control", "no-store");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        }

        private static void configureEnvironment(
                Map<String, String> environment,
                HttpServer jwkServer
        ) {
            environment.keySet().removeIf(key -> key.startsWith("ROUND_")
                    || key.startsWith("SPRING_")
                    || key.startsWith("SERVER_")
                    || key.startsWith("MANAGEMENT_")
                    || key.startsWith("LOGGING_")
                    || key.startsWith("TURN_")
                    || key.startsWith("SIGNALING_")
                    || key.startsWith("MAX_SIGNALING_")
                    || key.startsWith("JAVA_")
                    || key.startsWith("JDK_")
                    || key.equals("PORT")
                    || key.equals("HOST")
                    || key.equals("ALLOWED_ORIGINS")
                    || key.equals("JAVA_TOOL_OPTIONS")
                    || key.equals("_JAVA_OPTIONS"));
            environment.put("TZ", "UTC");
            environment.put("ROUND_AUTH_MODE", "baton");
            environment.put("ROUND_AUTH_COOKIE_NAME", COOKIE_NAME);
            environment.put("ROUND_AUTH_ISSUER", ISSUER);
            environment.put("ROUND_AUTH_AUDIENCE", AUDIENCE);
            environment.put(
                    "ROUND_AUTH_JWK_SET_URI",
                    "http://127.0.0.1:" + jwkServer.getAddress().getPort() + "/jwks"
            );
            environment.put("ROUND_AUTH_MAX_GRANT_LIFETIME_SECONDS", "300");
            environment.put("ALLOWED_ORIGINS", ALLOWED_ORIGIN);
            environment.put("TURN_URLS", "");
            environment.put("TURN_SHARED_SECRET", "");
        }

        private static Path javaExecutable() {
            return Path.of(
                    System.getProperty("java.home"),
                    "bin",
                    isWindows() ? "java.exe" : "java"
            );
        }

        private static boolean isWindows() {
            return System.getProperty("os.name")
                    .toLowerCase(java.util.Locale.ROOT)
                    .contains("win");
        }

        private static void readLogs(
                Process process,
                StringBuffer logs,
                CompletableFuture<Integer> port
        ) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(),
                    StandardCharsets.UTF_8
            ))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    appendBounded(logs, line);
                    Matcher matcher = TOMCAT_PORT.matcher(line);
                    if (matcher.find()) {
                        port.complete(Integer.parseInt(matcher.group(1)));
                    }
                }
                if (!port.isDone()) {
                    port.completeExceptionally(new IllegalStateException(
                            "ROUND signaling이 port를 알리기 전에 종료되었습니다. exit="
                                    + process.exitValue() + "\n" + logs
                    ));
                }
            } catch (Exception exception) {
                port.completeExceptionally(exception);
            }
        }

        private static void appendBounded(StringBuffer logs, String line) {
            synchronized (logs) {
                if (logs.length() >= MAX_LOG_CHARACTERS) {
                    return;
                }
                int remaining = MAX_LOG_CHARACTERS - logs.length();
                String appended = line + System.lineSeparator();
                logs.append(appended, 0, Math.min(remaining, appended.length()));
            }
        }

        private static void stopProcess(Process process) {
            if (process == null || !process.isAlive()) {
                return;
            }
            process.destroy();
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(5, TimeUnit.SECONDS);
                }
            } catch (InterruptedException exception) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }

        private static void removeShutdownHook(Thread shutdownHook) {
            if (shutdownHook == null) {
                return;
            }
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
                // JVM 종료 중에는 등록된 hook이 직접 프로세스를 정리한다.
            }
        }

        @Override
        public void close() {
            removeShutdownHook(processShutdownHook);
            stopProcess(process);
            logReader.interrupt();
            jwkServer.stop(0);
        }
    }
}
