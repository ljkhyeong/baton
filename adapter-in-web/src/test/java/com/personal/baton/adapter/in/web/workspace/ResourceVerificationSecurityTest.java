package com.personal.baton.adapter.in.web.workspace;

import com.personal.baton.adapter.in.web.auth.AuthenticatedAccountPrincipal;
import com.personal.baton.adapter.in.web.config.SecurityConfig;
import com.personal.baton.adapter.in.web.config.WebFilterConfig;
import com.personal.baton.application.identity.port.in.ValidateAccountSessionUseCase;
import com.personal.baton.application.workspace.port.in.ResourceVerificationUseCase;
import com.personal.baton.application.workspace.port.in.WorkspaceNotificationUseCase;
import com.personal.baton.application.workspace.port.in.NotificationPreferencesUseCase;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import com.personal.baton.application.workspace.port.in.ResourceVerificationUseCase.VerificationHistoryResult;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;

@WebMvcTest({ResourceVerificationController.class, WorkspaceNotificationController.class, NotificationPreferencesController.class})
@Import({SecurityConfig.class, WebFilterConfig.class, ResourceVerificationSecurityTest.PasswordConfig.class})
class ResourceVerificationSecurityTest {
    private static final UUID ID = UUID.fromString("00000000-0000-4000-8000-000000000001");
    @Autowired MockMvc mvc;
    @MockitoBean ResourceVerificationUseCase useCase;
    @MockitoBean WorkspaceNotificationUseCase notifications;
    @MockitoBean NotificationPreferencesUseCase preferences;
    @MockitoBean ValidateAccountSessionUseCase sessions;

    @Test
    @DisplayName("자료 확인은 공유 키만으로 기록할 수 없고 계정 세션과 CSRF 및 동일 출처를 모두 요구한다")
    void requiresAccountCsrfAndSameOrigin() throws Exception {
        when(sessions.isAccountSessionCurrent(any(), anyLong())).thenReturn(true);
        var auth = UsernamePasswordAuthenticationToken.authenticated(new Principal(ID, 0), null, List.of());
        mvc.perform(request().with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(request().with(authentication(auth))).andExpect(status().isForbidden());
        mvc.perform(request().with(authentication(auth)).with(csrf()).header("Origin", "https://foreign.example"))
                .andExpect(status().isForbidden());
        var schedule = post(ResourceVerificationController.SCHEDULE_PATH, ID, ID, ID)
                .header("Origin", "http://localhost").header("Sec-Fetch-Site", "same-origin").header("X-Baton-Access-Key", "key").contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedAccountId\":\"" + ID + "\",\"expectedVersion\":-1,\"intervalDays\":30,\"nextReviewOn\":\"2026-09-05\"}");
        mvc.perform(schedule.with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(post(ResourceVerificationController.SCHEDULE_PATH, ID, ID, ID).with(authentication(auth)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(useCase);
        when(useCase.verify(any(), any(), any(), any(), any(), any()))
                .thenReturn(new VerificationHistoryResult(ID, ID, ID, 0, List.of()));
        mvc.perform(request().with(authentication(auth)).with(csrf()))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        verify(useCase).verify(eq(ID), eq(ID), eq(ID), eq("key"), eq(ID), any());
    }

    @Test
    @DisplayName("내 알림은 계정 세션을 요구하고 읽음 변경에도 CSRF를 적용한다")
    void protectsPersonalNotifications() throws Exception {
        when(sessions.isAccountSessionCurrent(any(), anyLong())).thenReturn(true);
        mvc.perform(get(WorkspaceNotificationController.PATH, ID, ID)).andExpect(status().isUnauthorized());
        var auth = UsernamePasswordAuthenticationToken.authenticated(new Principal(ID, 0), null, List.of());
        mvc.perform(post(WorkspaceNotificationController.READ_PATH, ID, ID, ID).with(authentication(auth))
                        .header("Origin", "http://localhost").header("Sec-Fetch-Site", "same-origin"))
                .andExpect(status().isForbidden());
        mvc.perform(get(NotificationPreferencesController.PATH)).andExpect(status().isUnauthorized());
        mvc.perform(post(NotificationPreferencesController.PATH).with(authentication(auth))).andExpect(status().isForbidden());
        mvc.perform(post(NotificationPreferencesController.PATH).with(authentication(auth)).with(csrf())
                        .header("Origin", "http://localhost").header("Sec-Fetch-Site", "same-origin").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedAccountId":"%s","expectedVersion":-1,"deadlineSoonEnabled":true,"overdueEnabled":true,"handoffEnabled":true,"deadlineLeadHours":24}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isConflict());
        verifyNoInteractions(notifications, preferences);
    }

    private MockHttpServletRequestBuilder request() {
        return post(ResourceVerificationController.PATH, ID, ID, ID).header("X-Baton-Access-Key", "key")
                .header("Origin", "http://localhost").header("Sec-Fetch-Site", "same-origin")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedAccountId\":\"" + ID + "\",\"resourceVersion\":0,\"status\":\"CONFIRMED\"}");
    }
    private record Principal(UUID accountId, long sessionVersion) implements AuthenticatedAccountPrincipal {}
    @TestConfiguration(proxyBeanMethods = false)
    static class PasswordConfig {
        @Bean PasswordEncoder passwordEncoder() { return PasswordEncoderFactories.createDelegatingPasswordEncoder(); }
    }
}
