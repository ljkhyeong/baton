package com.personal.baton.adapter.out.external.watch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.personal.baton.application.watch.port.out.WatchMonitorInspectionPort.*;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class RestClientWatchInspectionClientTest {
    private MockRestServiceServer server;
    private RestClientWatchInspectionClient client;
    private static final String URL = "https://watch.example.com/api/v1/resource-monitors/resource";

    @BeforeEach
    void setUp() {
        var builder = RestClient.builder().baseUrl("https://watch.example.com")
                .defaultHeader("Authorization", "Bearer test-token");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new RestClientWatchInspectionClient(builder.build());
    }

    @Test
    @DisplayName("상태 조회는 서비스 인증과 요청 자료 결합을 검증하고 WATCH의 추가 필드는 허용한다")
    void readsBoundMonitor() {
        server.expect(requestTo(URL)).andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer test-token"))
                .andRespond(withSuccess(body("resource"), MediaType.APPLICATION_JSON));
        var result = client.inspect("resource");
        assertThat(result.status()).isEqualTo(LookupStatus.FOUND);
        assertThat(result.sourceRevision()).isEqualTo(7);
        assertThat(result.lastCheckedAt()).isEqualTo(Instant.parse("2026-09-05T01:00:00Z"));
        server.verify();
    }

    @Test
    @DisplayName("다른 자료 응답과 과대 본문 및 리디렉션을 상태 조회 실패로 처리한다")
    void rejectsUntrustedResponse() {
        server.expect(requestTo(URL)).andRespond(withSuccess(body("different"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(URL)).andRespond(withSuccess(" ".repeat(8193), MediaType.APPLICATION_JSON));
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TEMPORARY_REDIRECT).header("Location", "https://other.example.com"));
        for (int i = 0; i < 3; i++) assertThat(client.inspect("resource").status()).isEqualTo(LookupStatus.UNAVAILABLE);
        server.verify();
    }

    @Test
    @DisplayName("재점검은 본문 없이 접수하고 429의 재요청 대기 초를 보존한다")
    void requestsCheckAndPreservesRetry() {
        server.expect(requestTo(URL + "/check-requests")).andExpect(method(HttpMethod.POST))
                .andExpect(content().string(""))
                .andRespond(withStatus(HttpStatus.ACCEPTED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"status\":\"ALREADY_SCHEDULED\",\"nextCheckAt\":\"2026-09-05T01:00:00Z\"}"));
        server.expect(requestTo(URL + "/check-requests")).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "17"));
        assertThat(client.requestCheck("resource").status()).isEqualTo(CheckStatus.ALREADY_SCHEDULED);
        assertThat(client.requestCheck("resource").retryAfterSeconds()).isEqualTo(17);
        server.verify();
    }

    private String body(String reference) {
        return """
                {"resourceReference":"%s", "sourceRevision":7, "monitoringState":"ACTIVE",
                 "health":"HEALTHY", "lastCheckedAt":"2026-09-05T01:00:00Z", "consecutiveFailures":0,
                 "lastConclusiveAt":"2026-09-05T01:00:00Z", "lastOutcome":"SUCCESS", "nextCheckAt":null}
                """.formatted(reference);
    }
}
