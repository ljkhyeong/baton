package com.personal.baton.adapter.out.external.watch;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.personal.baton.application.watch.WatchMonitoringState;
import com.personal.baton.application.watch.WatchResourceHealth;
import com.personal.baton.application.watch.port.out.WatchMonitorInspectionPort;
import java.time.Instant;
import java.util.concurrent.Semaphore;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

public final class RestClientWatchInspectionClient implements WatchMonitorInspectionPort {
    private static final String PATH = "/api/v1/resource-monitors/{resourceReference}";
    private static final int MAX_RESPONSE_BYTES = 8_192;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final RestClient client;
    private final Semaphore capacity = new Semaphore(4);

    RestClientWatchInspectionClient(RestClient client) { this.client = client; }

    @Override
    public Inspection inspect(String resourceReference) {
        if (!capacity.tryAcquire()) return Inspection.unavailable();
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
                                || monitor.monitoringState() == null || monitor.health() == null) {
                            return Inspection.unavailable();
                        }
                        return new Inspection(LookupStatus.FOUND, monitor.sourceRevision(),
                                monitor.monitoringState(), monitor.health(),
                                monitor.lastCheckedAt() == null ? null : Instant.parse(monitor.lastCheckedAt()));
                    });
        } catch (RuntimeException exception) {
            return Inspection.unavailable();
        } finally {
            capacity.release();
        }
    }

    @Override
    public CheckRequest requestCheck(String resourceReference) {
        if (!capacity.tryAcquire()) return CheckRequest.unavailable();
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
            capacity.release();
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
                                   String lastCheckedAt) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CheckResponse(CheckStatus status) { }
}
