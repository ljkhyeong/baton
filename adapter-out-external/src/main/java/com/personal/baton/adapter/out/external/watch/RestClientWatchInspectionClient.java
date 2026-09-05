package com.personal.baton.adapter.out.external.watch;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.personal.baton.application.watch.WatchMonitoringState;
import com.personal.baton.application.watch.WatchCheckOutcome;
import com.personal.baton.application.watch.WatchResourceHealth;
import com.personal.baton.application.watch.port.out.WatchMonitorInspectionPort;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.EnumFeature;

public final class RestClientWatchInspectionClient implements WatchMonitorInspectionPort {
    private static final String PATH = "/api/v1/resource-monitors/{resourceReference}";
    private static final int MAX_RESPONSE_BYTES = 8_192;
    private static final JsonMapper JSON = JsonMapper.builder()
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .build();
    private final RestClient client;
    private final Semaphore lookupCallers = new Semaphore(32);
    private final Semaphore lookupConnections = new Semaphore(3, true);
    private final Semaphore checkConnection = new Semaphore(1);
    private final ConcurrentHashMap<String, CompletableFuture<Inspection>> lookups = new ConcurrentHashMap<>();

    RestClientWatchInspectionClient(RestClient client) { this.client = client; }

    @Override
    public Inspection inspect(String resourceReference) {
        if (!lookupCallers.tryAcquire()) return Inspection.unavailable();
        var pending = new CompletableFuture<Inspection>();
        var existing = lookups.putIfAbsent(resourceReference, pending);
        try {
            // 진행 중인 응답만 공유한다. 완료 결과를 저장해 다음 점검이나 리비전 변경을 가리지 않는다.
            if (existing != null) return existing.get(4, TimeUnit.SECONDS);
            var result = inspectOnce(resourceReference);
            pending.complete(result);
            return result;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return Inspection.unavailable();
        } catch (ExecutionException | TimeoutException exception) {
            return Inspection.unavailable();
        } finally {
            if (existing == null) {
                pending.complete(Inspection.unavailable());
                lookups.remove(resourceReference, pending);
            }
            lookupCallers.release();
        }
    }

    private Inspection inspectOnce(String resourceReference) throws InterruptedException {
        if (!lookupConnections.tryAcquire(1, TimeUnit.SECONDS)) return Inspection.unavailable();
        try {
            return client.get().uri(PATH, resourceReference).accept(MediaType.APPLICATION_JSON)
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == 404) return Inspection.missing();
                        if (status != 200) return Inspection.unavailable();
                        byte[] body = response.getBody().readNBytes(MAX_RESPONSE_BYTES + 1);
                        if (body.length > MAX_RESPONSE_BYTES) return Inspection.unavailable();
                        MonitorResponse monitor = JSON.readValue(body, MonitorResponse.class);
                        if (monitor == null || !resourceReference.equals(monitor.resourceReference())
                                || monitor.sourceRevision() == null || monitor.sourceRevision() < 1
                                || monitor.monitoringState() == null || monitor.health() == null
                                || monitor.consecutiveFailures() == null || monitor.consecutiveFailures() < 0) {
                            return Inspection.unavailable();
                        }
                        return new Inspection(LookupStatus.FOUND, monitor.sourceRevision(),
                                monitor.monitoringState(), monitor.health(),
                                monitor.lastCheckedAt() == null ? null : Instant.parse(monitor.lastCheckedAt()),
                                monitor.lastOutcome(), monitor.consecutiveFailures());
                    });
        } catch (RuntimeException exception) {
            return Inspection.unavailable();
        } finally {
            lookupConnections.release();
        }
    }

    @Override
    public CheckRequest requestCheck(String resourceReference) {
        if (!checkConnection.tryAcquire()) return CheckRequest.unavailable();
        try {
            return client.post().uri(PATH + "/check-requests", resourceReference)
                    .accept(MediaType.APPLICATION_JSON).exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == 429) {
                            return new CheckRequest(CheckStatus.RATE_LIMITED,
                                    retryAfter(response.getHeaders().getFirst("Retry-After")));
                        }
                        if (status == 404 || status == 409) return new CheckRequest(CheckStatus.INACTIVE, null);
                        if (status != 202) return CheckRequest.unavailable();
                        byte[] body = response.getBody().readNBytes(MAX_RESPONSE_BYTES + 1);
                        if (body.length > MAX_RESPONSE_BYTES) return CheckRequest.unavailable();
                        CheckResponse receipt = JSON.readValue(body, CheckResponse.class);
                        if (receipt == null || receipt.status() == null) return CheckRequest.unavailable();
                        return switch (receipt.status()) {
                            case SCHEDULED, ALREADY_SCHEDULED, IN_PROGRESS -> new CheckRequest(receipt.status(), null);
                            default -> CheckRequest.unavailable();
                        };
                    });
        } catch (RuntimeException exception) {
            return CheckRequest.unavailable();
        } finally {
            checkConnection.release();
        }
    }

    private long retryAfter(String value) {
        try {
            long seconds = Long.parseLong(value);
            return Math.clamp(seconds, 1, 3_600);
        } catch (NumberFormatException exception) {
            return 30;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record MonitorResponse(String resourceReference, Long sourceRevision,
                                   WatchMonitoringState monitoringState, WatchResourceHealth health,
                                   String lastCheckedAt, WatchCheckOutcome lastOutcome, Integer consecutiveFailures) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CheckResponse(CheckStatus status) { }
}
