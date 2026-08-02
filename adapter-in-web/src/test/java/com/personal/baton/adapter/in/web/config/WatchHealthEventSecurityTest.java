package com.personal.baton.adapter.in.web.config;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.personal.baton.adapter.in.web.RequestIdFilter;
import com.personal.baton.adapter.in.web.watch.WatchHealthEventController;
import com.personal.baton.application.watch.port.in.AcceptWatchHealthEventUseCase;
import com.personal.baton.application.watch.port.in.AcceptWatchHealthEventUseCase.AcceptWatchHealthEventCommand;
import com.personal.baton.application.watch.port.in.AcceptWatchHealthEventUseCase.WatchHealthEventReceipt;
import com.personal.baton.bootstrap.config.WatchEventReceiverConfig;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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

    private static final UUID EVENT_ID =
            UUID.fromString("8cf76651-f98d-4755-b578-1629b0ca2f55");
    private static final String TOKEN = "receiver-token-with-at-least-32-characters";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AcceptWatchHealthEventUseCase useCase;

    @DisplayName("올바른 WATCH bearer token은 CSRF token 없이 이벤트 수신 controller에 진입한다")
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

    @DisplayName("누락된 WATCH bearer token은 잘못된 본문을 읽기 전에 일반화된 401로 거부한다")
    @Test
    void rejectMissingTokenBeforeReadingBody() throws Exception {
        mockMvc.perform(post(WatchHealthEventController.PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(header().exists(RequestIdFilter.HEADER_NAME))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("인증 정보가 올바르지 않습니다"));

        verifyNoInteractions(useCase);
    }

    @DisplayName("틀린 WATCH bearer token은 누락된 token과 같은 401 계약으로 거부한다")
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

    @DisplayName("Authorization header가 중복되면 올바른 token을 포함해도 거부한다")
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

    @DisplayName("인증된 WATCH 요청의 잘못된 JSON은 공통 400 오류로 변환된다")
    @Test
    void mapMalformedAuthenticatedRequestToBadRequest() throws Exception {
        mockMvc.perform(post(WatchHealthEventController.PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                        .header("Idempotency-Key", EVENT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));

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

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AcceptWatchHealthEventUseCase useCase;

    @DisplayName("기본 비활성 WATCH 이벤트 수신 경로는 제시된 token과 본문을 검사하지 않고 401을 반환한다")
    @Test
    void rejectRequestWhileReceiverIsDisabled() throws Exception {
        mockMvc.perform(post(WatchHealthEventController.PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer any-presented-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("인증 정보가 올바르지 않습니다"));

        verifyNoInteractions(useCase);
    }
}
