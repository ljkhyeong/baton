package com.personal.baton.adapter.in.web.config;

import com.personal.baton.application.identity.port.in.ValidateAccountSessionUseCase;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.personal.baton.adapter.in.web.watch.WatchHealthEventController;
import com.personal.baton.application.watch.port.in.AcceptWatchHealthEventUseCase;
import com.personal.baton.application.watch.port.in.AcceptWatchHealthEventUseCase.AcceptWatchHealthEventCommand;
import com.personal.baton.application.watch.port.in.AcceptWatchHealthEventUseCase.WatchHealthEventReceipt;
import com.personal.baton.bootstrap.config.WatchEventReceiverConfig;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = WatchHealthEventController.class)
@Import({SecurityConfig.class, WebFilterConfig.class, WatchEventReceiverConfig.class})
@TestPropertySource(properties = {
        "baton.watch.source-namespace=study-pilot",
        "baton.watch.event-receiver.enabled=true",
        "baton.watch.event-receiver.bearer-token=receiver-token-with-at-least-32-characters"
})
class WatchHealthEventSecurityTest {

    @MockitoBean
    private ValidateAccountSessionUseCase validateAccountSessionUseCase;

    private static final UUID EVENT_ID =
            UUID.fromString("8cf76651-f98d-4755-b578-1629b0ca2f55");
    private static final String TOKEN = "receiver-token-with-at-least-32-characters";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AcceptWatchHealthEventUseCase useCase;

    @MockitoBean
    private PasswordEncoder passwordEncoder;

    @DisplayName("올바른 WATCH Bearer 토큰은 CSRF 토큰 없이 이벤트 수신 컨트롤러에 진입한다")
    @Test
    void acceptAuthenticatedRequestWithoutCsrf() throws Exception {
        when(useCase.accept(eq(EVENT_ID), any(AcceptWatchHealthEventCommand.class)))
                .thenReturn(new WatchHealthEventReceipt(
                        EVENT_ID,
                        Instant.parse("2026-08-02T03:04:06Z")
                ));

        mockMvc.perform(post(WatchHealthEventController.PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                        .header("Idempotency-Key", EVENT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.eventId").value(EVENT_ID.toString()));
    }

    @DisplayName("WATCH 수신 경로가 인코딩되어도 인증 토큰이 없으면 이벤트를 접수하지 않는다")
    @ParameterizedTest
    @ValueSource(strings = {
            WatchHealthEventController.PATH,
            "/api/v1/internal/resource-health-%65vents"
    })
    void rejectMissingToken(String path) throws Exception {
        mockMvc.perform(post(URI.create(path))
                        .header("Idempotency-Key", EVENT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        verifyNoInteractions(useCase);
    }

    @DisplayName("틀린 WATCH Bearer 토큰은 누락된 토큰과 같은 401 계약으로 거부한다")
    @Test
    void rejectInvalidTokenGenerically() throws Exception {
        mockMvc.perform(post(WatchHealthEventController.PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer invalid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("인증 정보가 올바르지 않습니다"));

        verifyNoInteractions(useCase);
    }

    @DisplayName("Authorization 헤더가 중복되면 올바른 토큰을 포함해도 거부한다")
    @Test
    void rejectDuplicateAuthorizationHeaders() throws Exception {
        mockMvc.perform(post(WatchHealthEventController.PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN, "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        verifyNoInteractions(useCase);
    }

    private String validRequest() {
        return """
                {
                  "eventId": "8cf76651-f98d-4755-b578-1629b0ca2f55",
                  "eventType": "RESOURCE_HEALTH_CHANGED",
                  "resourceReference": "baton-manager:study-pilot:role-resource:aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                  "sourceRevision": 7,
                  "attemptId": "81ccb9da-f9f9-4abc-87fe-cf6193ee5f79",
                  "previousHealth": "DEGRADED",
                  "currentHealth": "BROKEN",
                  "changedAt": "2026-08-02T03:04:05Z"
                }
                """;
    }
}

@WebMvcTest(controllers = WatchHealthEventController.class)
@Import({SecurityConfig.class, WebFilterConfig.class, WatchEventReceiverConfig.class})
@TestPropertySource(properties = {
        "baton.watch.source-namespace=study-pilot",
        "baton.watch.event-receiver.enabled=false"
})
class DisabledWatchHealthEventSecurityTest {

    @MockitoBean
    private ValidateAccountSessionUseCase validateAccountSessionUseCase;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AcceptWatchHealthEventUseCase useCase;

    @MockitoBean
    private PasswordEncoder passwordEncoder;

    @DisplayName("WATCH 수신기가 꺼져 있으면 인코딩된 경로도 본문을 읽기 전에 거부한다")
    @ParameterizedTest
    @ValueSource(strings = {
            WatchHealthEventController.PATH,
            "/api/v1/internal/resource-health-%65vents"
    })
    void rejectRequestWhileReceiverIsDisabled(String path) throws Exception {
        mockMvc.perform(post(URI.create(path))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer any-presented-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("인증 정보가 올바르지 않습니다"));

        verifyNoInteractions(useCase);
    }
}
