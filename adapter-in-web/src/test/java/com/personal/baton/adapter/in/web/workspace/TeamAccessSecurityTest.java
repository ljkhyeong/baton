package com.personal.baton.adapter.in.web.workspace;

import com.personal.baton.adapter.in.web.auth.AuthenticatedAccountPrincipal;
import com.personal.baton.adapter.in.web.config.SecurityConfig;
import com.personal.baton.adapter.in.web.config.WebFilterConfig;
import com.personal.baton.application.identity.port.in.ValidateAccountSessionUseCase;
import com.personal.baton.application.workspace.port.in.TeamAccessUseCase;
import com.personal.baton.application.workspace.port.in.WorkspaceRecordsUseCase;
import com.personal.baton.domain.workspace.DomainValidationException;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;

@WebMvcTest({TeamAccessController.class, WorkspaceRecordsController.class})
@Import({SecurityConfig.class, WebFilterConfig.class, TeamAccessSecurityTest.PasswordConfig.class})
class TeamAccessSecurityTest {
    private static final UUID ID = UUID.fromString("00000000-0000-4000-8000-000000000001");
    @Autowired MockMvc mvc;
    @MockitoBean TeamAccessUseCase access;
    @MockitoBean WorkspaceRecordsUseCase records;
    @MockitoBean ValidateAccountSessionUseCase sessions;

    @Test
    @DisplayName("초대 관리는 공유 키만으로 접근할 수 없고 계정·CSRF·동일 출처와 화면의 계정 확인을 요구한다")
    void protectsInvitationAdministration() throws Exception {
        when(sessions.isAccountSessionCurrent(any(), anyLong())).thenReturn(true);
        var auth = UsernamePasswordAuthenticationToken.authenticated(new Principal(ID, 0), null, List.of());
        mvc.perform(get(TeamAccessController.PATH, ID).header("X-Baton-Access-Key", "key"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(TeamAccessController.MY_TEAMS_PATH).header("X-Baton-Access-Key", "key"))
                .andExpect(status().isUnauthorized());
        mvc.perform(invite().with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(invite().with(authentication(auth))).andExpect(status().isForbidden());
        mvc.perform(invite().with(authentication(auth)).with(csrf()).header("Origin", "https://foreign.example"))
                .andExpect(status().isForbidden());
        mvc.perform(invite().with(authentication(auth)).with(csrf()).content("{\"expectedAccountId\":\"" + UUID.randomUUID()
                        + "\",\"memberId\":\"" + ID + "\",\"permission\":\"MEMBER\"}"))
                .andExpect(status().isConflict());
        verifyNoInteractions(access);
    }

    @Test
    @DisplayName("로그인한 작업 공간 변경은 공유 키가 있어도 CSRF와 화면에서 확인한 계정 헤더를 모두 검사한다")
    void bindsWorkspaceMutationToExpectedAccount() throws Exception {
        when(sessions.isAccountSessionCurrent(any(), anyLong())).thenReturn(true);
        var auth = UsernamePasswordAuthenticationToken.authenticated(new Principal(ID, 0), null, List.of());
        mvc.perform(decision().with(authentication(auth)).header("X-Baton-Account-Id", ID))
                .andExpect(status().isForbidden());
        mvc.perform(decision().with(authentication(auth)).with(csrf()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCOUNT_CHANGED"));
        mvc.perform(decision().with(authentication(auth)).with(csrf()).header("X-Baton-Account-Id", UUID.randomUUID()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(records);
        when(records.createDecision(any(), any(), any(), any(), any()))
                .thenThrow(new DomainValidationException("업무 규칙 검사 도달"));
        mvc.perform(decision().with(authentication(auth)).with(csrf()).header("X-Baton-Account-Id", ID))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("업무 규칙 검사 도달"));
        verify(records).createDecision(eq(ID), eq(ID), any(), eq("key"), any());
    }

    private MockHttpServletRequestBuilder invite() {
        return post(TeamAccessController.PATH + "/invitations", ID).header("Origin", "http://localhost")
                .header("Sec-Fetch-Site", "same-origin").contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedAccountId\":\"" + ID + "\",\"memberId\":\"" + ID + "\",\"permission\":\"MEMBER\"}");
    }
    private MockHttpServletRequestBuilder decision() {
        return post("/api/v1/teams/{teamId}/seasons/{seasonId}/decisions", ID, ID)
                .header("X-Baton-Access-Key", "key").header("Idempotency-Key", UUID.randomUUID())
                .header("Origin", "http://localhost").header("Sec-Fetch-Site", "same-origin")
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"결정\",\"reason\":\"이유\",\"authorMemberId\":\"" + ID + "\",\"roleIds\":[\"" + ID + "\"]}");
    }
    private record Principal(UUID accountId, long sessionVersion) implements AuthenticatedAccountPrincipal {}
    @TestConfiguration(proxyBeanMethods = false)
    static class PasswordConfig {
        @Bean PasswordEncoder passwordEncoder() { return PasswordEncoderFactories.createDelegatingPasswordEncoder(); }
    }
}
