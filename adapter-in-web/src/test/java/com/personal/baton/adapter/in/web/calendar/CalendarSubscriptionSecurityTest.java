package com.personal.baton.adapter.in.web.calendar;

import static org.mockito.ArgumentMatchers.any;

import static org.mockito.ArgumentMatchers.anyLong;

import org.junit.jupiter.api.BeforeEach;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.personal.baton.adapter.in.web.auth.AuthenticatedAccountPrincipal;
import com.personal.baton.adapter.in.web.config.SecurityConfig;
import com.personal.baton.application.identity.port.in.ValidateAccountSessionUseCase;
import com.personal.baton.application.calendar.port.in.CalendarSubscriptionUseCase.SubscriptionPage;
import com.personal.baton.adapter.in.web.config.WebFilterConfig;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = CalendarSubscriptionController.class)
@Import({
        SecurityConfig.class,
        WebFilterConfig.class,
        CalendarSubscriptionSecurityTest.PasswordEncoderTestConfig.class
})
class CalendarSubscriptionSecurityTest {

    @MockitoBean
    private ValidateAccountSessionUseCase validateAccountSessionUseCase;

    @BeforeEach
    void acceptCurrentAccountSessions() {
        when(validateAccountSessionUseCase.isAccountSessionCurrent(any(), anyLong())).thenReturn(true);
    }

    private static final UUID ACCOUNT_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000002631"
    );
    private static final UUID TEAM_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000002632"
    );
    private static final UUID SEASON_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000002633"
    );
    private static final String ACCESS_KEY = "workspace-access-key";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private com.personal.baton.application.calendar.port.in.CalendarSubscriptionUseCase subscriptions;

    @Test
    @DisplayName("구독 조회·발급·재발급·폐기는 실제 인증 필터에서 계정 세션을 요구한다")
    void requiresSessionForAllRoutes() throws Exception {
        for (var request : requests()) {
            mockMvc.perform(request.with(csrf()).header("X-Baton-Access-Key", ACCESS_KEY).header("X-Baton-Account-Id", ACCOUNT_ID)
                            .header("Origin", "http://localhost").header("Sec-Fetch-Site", "same-origin"))
                    .andExpect(status().isUnauthorized());
        }
        org.mockito.Mockito.verifyNoInteractions(subscriptions);
    }

    @Test
    @DisplayName("구독 변경은 CSRF 누락과 다른 출처를 거부한다")
    void rejectsUnprotectedMutations() throws Exception {
        for (int i = 1; i < 4; i++) {
            mockMvc.perform(requests().get(i).with(authentication(accountAuthentication()))
                            .header("X-Baton-Access-Key", ACCESS_KEY).header("X-Baton-Account-Id", ACCOUNT_ID).header("Origin", "http://localhost")
                            .header("Sec-Fetch-Site", "same-origin"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(requests().get(i).with(authentication(accountAuthentication())).with(csrf())
                            .header("X-Baton-Access-Key", ACCESS_KEY).header("X-Baton-Account-Id", ACCOUNT_ID).header("Origin", "https://elsewhere.example")
                            .header("Sec-Fetch-Site", "cross-site"))
                    .andExpect(status().isForbidden());
        }
        org.mockito.Mockito.verifyNoInteractions(subscriptions);
    }

    @Test
    @DisplayName("인증된 구독 발급은 세션 계정으로만 요청하며 주소 응답을 캐시하지 않는다")
    void createsForAuthenticatedOwner() throws Exception {
        var scope = new com.personal.baton.application.calendar.port.in.CalendarSubscriptionUseCase.Scope(ACCOUNT_ID, TEAM_ID, SEASON_ID, ACCESS_KEY);
        when(subscriptions.create(scope)).thenReturn(new com.personal.baton.application.calendar.port.in.CalendarSubscriptionUseCase.Credential(
                UUID.randomUUID(), SEASON_ID, java.net.URI.create("https://cal.b4ton.com/calendars/v1/" + "a".repeat(43) + ".ics")));
        mockMvc.perform(post(CalendarSubscriptionController.PATH, TEAM_ID, SEASON_ID)
                        .with(authentication(accountAuthentication())).with(csrf())
                        .header("X-Baton-Access-Key", ACCESS_KEY).header("X-Baton-Account-Id", ACCOUNT_ID).header("Origin", "http://localhost")
                        .header("Sec-Fetch-Site", "same-origin"))
                .andExpect(status().isCreated()).andExpect(header().string("Cache-Control", "no-store"));
        org.mockito.Mockito.verify(subscriptions).create(scope);
    }

    @Test
    @DisplayName("화면의 계정과 현재 세션 계정이 다르면 구독 작업에 진입하지 않는다")
    void rejectsAccountChangedInAnotherTab() throws Exception {
        for (var request : requests()) {
            mockMvc.perform(request.with(authentication(accountAuthentication())).with(csrf())
                            .header("X-Baton-Access-Key", ACCESS_KEY).header("X-Baton-Account-Id", UUID.randomUUID())
                            .header("Origin", "http://localhost").header("Sec-Fetch-Site", "same-origin"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("CAL_SUBSCRIPTION_ACCOUNT_CHANGED"));
        }
        org.mockito.Mockito.verifyNoInteractions(subscriptions);
    }

    @Test
    @DisplayName("접근 키 없이도 세션 소유자의 상태 조회와 CSRF로 보호한 구독 폐기가 가능하다")
    void allowsOwnerCleanupWithoutWorkspaceKey() throws Exception {
        when(subscriptions.list(ACCOUNT_ID, null)).thenReturn(
                new SubscriptionPage(List.of(), null));
        mockMvc.perform(get(CalendarSubscriptionController.LIST_PATH)
                        .with(authentication(accountAuthentication())).header("X-Baton-Account-Id", ACCOUNT_ID))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.accountId").value(ACCOUNT_ID.toString()))
                .andExpect(jsonPath("$.subscriptions").isEmpty());
        org.mockito.Mockito.verify(subscriptions).list(ACCOUNT_ID, null);
        var scope = new com.personal.baton.application.calendar.port.in.CalendarSubscriptionUseCase.Scope(ACCOUNT_ID, TEAM_ID, SEASON_ID, "");
        when(subscriptions.find(scope)).thenReturn(new com.personal.baton.application.calendar.port.in.CalendarSubscriptionUseCase.Subscription(
                UUID.randomUUID(), SEASON_ID, com.personal.baton.application.calendar.port.in.CalendarSubscriptionUseCase.Status.ACTIVE));
        mockMvc.perform(get(CalendarSubscriptionController.PATH, TEAM_ID, SEASON_ID)
                        .with(authentication(accountAuthentication())).header("X-Baton-Account-Id", ACCOUNT_ID))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(CalendarSubscriptionController.PATH, TEAM_ID, SEASON_ID)
                        .with(authentication(accountAuthentication())).with(csrf()).header("X-Baton-Account-Id", ACCOUNT_ID)
                        .header("Origin", "http://localhost").header("Sec-Fetch-Site", "same-origin"))
                .andExpect(status().isNoContent());
        org.mockito.Mockito.verify(subscriptions).revoke(scope);
    }

    private List<org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder> requests() {
        return List.of(get(CalendarSubscriptionController.PATH, TEAM_ID, SEASON_ID),
                post(CalendarSubscriptionController.PATH, TEAM_ID, SEASON_ID),
                post(CalendarSubscriptionController.ROTATE_PATH, TEAM_ID, SEASON_ID),
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(CalendarSubscriptionController.PATH, TEAM_ID, SEASON_ID),
                get(CalendarSubscriptionController.LIST_PATH));
    }

    private UsernamePasswordAuthenticationToken accountAuthentication() {
        return UsernamePasswordAuthenticationToken.authenticated(
                new TestAccountPrincipal(ACCOUNT_ID),
                null,
                List.of()
        );
    }

    private record TestAccountPrincipal(UUID accountId)
            implements AuthenticatedAccountPrincipal {
        @Override
        public long sessionVersion() {
            return 0;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class PasswordEncoderTestConfig {

        @Bean
        PasswordEncoder passwordEncoder() {
            return PasswordEncoderFactories.createDelegatingPasswordEncoder();
        }
    }
}
