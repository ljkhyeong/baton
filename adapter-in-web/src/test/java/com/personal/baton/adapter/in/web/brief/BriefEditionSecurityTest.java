package com.personal.baton.adapter.in.web.brief;

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
import com.personal.baton.adapter.in.web.config.WebFilterConfig;
import com.personal.baton.application.brief.BriefEditionSnapshot;
import com.personal.baton.application.brief.port.in.BriefEditionUseCase;
import com.personal.baton.application.brief.port.in.BriefEditionUseCase.GenerateEditionCommand;
import com.personal.baton.application.brief.port.in.BriefEditionUseCase.GenerationResult;
import com.personal.baton.application.brief.port.in.BriefEditionUseCase.LatestEditionQuery;
import com.personal.baton.application.brief.port.in.BriefEditionUseCase.LatestEditionResult;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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

@WebMvcTest(controllers = BriefEditionController.class)
@Import({
        SecurityConfig.class,
        WebFilterConfig.class,
        BriefEditionSecurityTest.PasswordEncoderTestConfig.class
})
class BriefEditionSecurityTest {

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
    private BriefEditionUseCase briefEditionUseCase;

    @DisplayName("BRIEF 최신 조회는 실제 filter chain에서 계정 세션을 요구한다")
    @Test
    void requiresAccountSessionForLatestEdition() throws Exception {
        mockMvc.perform(get(BriefEditionController.LATEST_PATH, TEAM_ID, SEASON_ID)
                        .header("X-Baton-Access-Key", ACCESS_KEY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @DisplayName("BRIEF 최신 조회는 인증 뒤 CSRF 없이 ETag 조건부 응답을 사용한다")
    @Test
    void usesStandardConditionalGetAfterAuthentication() throws Exception {
        when(briefEditionUseCase.findLatestEdition(new LatestEditionQuery(
                ACCOUNT_ID,
                TEAM_ID,
                SEASON_ID,
                ACCESS_KEY
        ))).thenReturn(new LatestEditionResult(snapshot(), "\"brief-edition-v1-test\""));

        mockMvc.perform(get(BriefEditionController.LATEST_PATH, TEAM_ID, SEASON_ID)
                        .with(authentication(accountAuthentication()))
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .header(HttpHeaders.IF_NONE_MATCH, "\"brief-edition-v1-test\""))
                .andExpect(status().isNotModified())
                .andExpect(header().string(
                        HttpHeaders.ETAG,
                        "\"brief-edition-v1-test\""
                ));
    }

    @DisplayName("BRIEF 생성은 계정 세션만으로는 부족하고 CSRF와 동일 출처를 요구한다")
    @Test
    void requiresCsrfForEditionGeneration() throws Exception {
        mockMvc.perform(post(BriefEditionController.GENERATION_PATH, TEAM_ID, SEASON_ID)
                        .with(authentication(accountAuthentication()))
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .header(HttpHeaders.ORIGIN, "http://localhost")
                        .header("Sec-Fetch-Site", "same-origin"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("REQUEST_FORBIDDEN"));
    }

    @DisplayName("BRIEF 생성은 계정 세션과 CSRF·동일 출처를 갖추면 application으로 진입한다")
    @Test
    void permitsEditionGenerationWithSessionAndCsrf() throws Exception {
        when(briefEditionUseCase.generateEdition(new GenerateEditionCommand(
                ACCOUNT_ID,
                TEAM_ID,
                SEASON_ID,
                ACCESS_KEY
        ))).thenReturn(new GenerationResult(
                UUID.fromString("00000000-0000-0000-0000-000000002634"),
                17,
                UUID.fromString("00000000-0000-0000-0000-000000002635"),
                3,
                17,
                "\"brief-edition-v1-test\"",
                true
        ));

        mockMvc.perform(post(BriefEditionController.GENERATION_PATH, TEAM_ID, SEASON_ID)
                        .with(authentication(accountAuthentication()))
                        .with(csrf())
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .header(HttpHeaders.ORIGIN, "http://localhost")
                        .header("Sec-Fetch-Site", "same-origin"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.deliveryWatermark").value(17));
    }

    private UsernamePasswordAuthenticationToken accountAuthentication() {
        return UsernamePasswordAuthenticationToken.authenticated(
                new TestAccountPrincipal(ACCOUNT_ID),
                null,
                List.of()
        );
    }

    private BriefEditionSnapshot snapshot() {
        return new BriefEditionSnapshot(
                UUID.fromString("00000000-0000-0000-0000-000000002635"),
                TEAM_ID,
                SEASON_ID,
                3,
                LocalDate.parse("2026-08-24"),
                ZoneId.of("Asia/Seoul"),
                Instant.parse("2026-08-23T15:00:00Z"),
                Instant.parse("2026-08-30T15:00:00Z"),
                17,
                Instant.parse("2026-08-29T03:00:00Z"),
                1,
                List.of()
        );
    }

    private record TestAccountPrincipal(UUID accountId)
            implements AuthenticatedAccountPrincipal {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class PasswordEncoderTestConfig {

        @Bean
        PasswordEncoder passwordEncoder() {
            return PasswordEncoderFactories.createDelegatingPasswordEncoder();
        }
    }
}
