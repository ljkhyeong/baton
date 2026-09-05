package com.personal.baton.adapter.in.web.restdocs;

import com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper;
import com.epages.restdocs.apispec.ConstrainedFields;
import com.personal.baton.adapter.in.web.GlobalExceptionHandler;
import com.personal.baton.adapter.in.web.auth.AuthenticatedAccountPrincipal;
import com.personal.baton.adapter.in.web.workspace.NotificationPreferencesController;
import com.personal.baton.adapter.in.web.workspace.NotificationPreferencesRequest;
import com.personal.baton.application.workspace.port.in.NotificationPreferencesUseCase;
import com.personal.baton.application.workspace.port.in.NotificationPreferencesUseCase.PreferencesResult;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.http.MediaType;
import org.springframework.restdocs.RestDocumentationContextProvider;
import org.springframework.restdocs.RestDocumentationExtension;
import org.springframework.restdocs.payload.ResponseFieldsSnippet;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.DefaultSecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.restdocs.headers.HeaderDocumentation.*;
import static org.springframework.restdocs.payload.PayloadDocumentation.*;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.documentationConfiguration;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Tag("restdocs")
@ExtendWith(RestDocumentationExtension.class)
class NotificationPreferencesRestDocsTest {
    private static final UUID ACCOUNT = UUID.fromString("00000000-0000-4000-8000-000000000004");
    private MockMvc mvc;
    private NotificationPreferencesUseCase useCase;
    @BeforeEach void setUp(RestDocumentationContextProvider documentation) {
        useCase = mock(NotificationPreferencesUseCase.class);
        mvc = MockMvcBuilders.standaloneSetup(new NotificationPreferencesController(useCase))
                .setControllerAdvice(new GlobalExceptionHandler()).setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .apply(springSecurity(new FilterChainProxy(new DefaultSecurityFilterChain(AnyRequestMatcher.INSTANCE,
                        new SecurityContextHolderFilter(new HttpSessionSecurityContextRepository())))))
                .apply(documentationConfiguration(documentation)).build();
    }
    @Test @DisplayName("개인 알림 설정은 현재 계정과 알림 종류별 사용 여부 및 사전 알림 시간을 반환한다")
    void documentsPreferences() throws Exception {
        when(useCase.get(ACCOUNT)).thenReturn(new PreferencesResult(ACCOUNT, -1, true, true, true, 24));
        mvc.perform(get(NotificationPreferencesController.PATH).with(authentication(auth())))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.accountId").value(ACCOUNT.toString())).andExpect(jsonPath("$.deadlineLeadHours").value(24))
                .andDo(MockMvcRestDocumentationWrapper.document("getNotificationPreferences", "현재 계정의 알림 설정을 조회하며 미설정이면 모든 종류와 24시간 전을 기본으로 반환한다.", "개인 알림 설정 조회",
                        responseHeaders(headerWithName("Cache-Control").description("개인 설정 캐시 금지")), fields()));
    }
    @Test @DisplayName("개인 알림 설정은 계정과 설정 버전을 대조해 변경한다")
    void documentsConfigure() throws Exception {
        when(useCase.configure(any(), any())).thenReturn(new PreferencesResult(ACCOUNT, 0, true, false, true, 48));
        var constrained = new ConstrainedFields(NotificationPreferencesRequest.class);
        mvc.perform(post(NotificationPreferencesController.PATH).with(authentication(auth()))
                        .header("Origin", "https://baton.example").header("Sec-Fetch-Site", "same-origin").header("X-CSRF-TOKEN", "csrf")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"expectedAccountId":"%s","expectedVersion":-1,"deadlineSoonEnabled":true,"overdueEnabled":false,"handoffEnabled":true,"deadlineLeadHours":48}
                                """.formatted(ACCOUNT)))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.overdueEnabled").value(false)).andExpect(jsonPath("$.deadlineLeadHours").value(48))
                .andDo(MockMvcRestDocumentationWrapper.document("configureNotificationPreferences", "현재 계정의 알림 종류별 사용 여부와 마감 1~168시간 전 기준을 저장한다.", "개인 알림 설정 변경",
                        requestHeaders(headerWithName("Origin").description("BATON 동일 출처"), headerWithName("Sec-Fetch-Site").description("same-origin"), headerWithName("X-CSRF-TOKEN").description("세션 CSRF 토큰")),
                        requestFields(constrained.withPath("expectedAccountId").description("현재 로그인 계정"), constrained.withPath("expectedVersion").description("조회한 설정 버전. 미설정은 -1"),
                                constrained.withPath("deadlineSoonEnabled").description("마감 임박 알림"), constrained.withPath("overdueEnabled").description("기한 지남 알림"),
                                constrained.withPath("handoffEnabled").description("바통 수락 요청 알림"), constrained.withPath("deadlineLeadHours").description("마감 1~168시간 전")),
                        responseHeaders(headerWithName("Cache-Control").description("개인 설정 캐시 금지")), fields()));
    }
    private ResponseFieldsSnippet fields() { return responseFields(fieldWithPath("accountId").description("현재 계정"), fieldWithPath("version").description("설정 버전. 미설정은 -1"),
            fieldWithPath("deadlineSoonEnabled").description("마감 임박 알림 사용"), fieldWithPath("overdueEnabled").description("기한 지남 알림 사용"),
            fieldWithPath("handoffEnabled").description("바통 수락 요청 알림 사용"), fieldWithPath("deadlineLeadHours").description("마감 몇 시간 전부터 표시할지")); }
    private UsernamePasswordAuthenticationToken auth() { return UsernamePasswordAuthenticationToken.authenticated(new Principal(ACCOUNT, 0), null, List.of()); }
    private record Principal(UUID accountId, long sessionVersion) implements AuthenticatedAccountPrincipal {}
}
