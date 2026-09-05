package com.personal.baton.adapter.in.web.restdocs;

import com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper;
import com.epages.restdocs.apispec.ConstrainedFields;
import com.personal.baton.adapter.in.web.GlobalExceptionHandler;
import com.personal.baton.adapter.in.web.auth.AuthenticatedAccountPrincipal;
import com.personal.baton.adapter.in.web.auth.AccountDeactivationController;
import com.personal.baton.adapter.in.web.auth.AccountDeactivationController.AccountDeactivationRequest;
import com.personal.baton.application.identity.error.AccountDeactivationBlockedException;
import com.personal.baton.application.identity.port.in.DeactivateAccountUseCase;
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
class AccountDeactivationRestDocsTest {
    private static final UUID ACCOUNT = UUID.fromString("00000000-0000-4000-8000-000000000004");
    private MockMvc mvc;
    private DeactivateAccountUseCase useCase;
    @BeforeEach void setUp(RestDocumentationContextProvider documentation) {
        useCase = mock(DeactivateAccountUseCase.class);
        mvc = MockMvcBuilders.standaloneSetup(new AccountDeactivationController(useCase))
                .setControllerAdvice(new GlobalExceptionHandler()).setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .apply(springSecurity(new FilterChainProxy(new DefaultSecurityFilterChain(AnyRequestMatcher.INSTANCE,
                        new SecurityContextHolderFilter(new HttpSessionSecurityContextRepository())))))
                .apply(documentationConfiguration(documentation)).build();
    }
    @Test @DisplayName("계정 비활성화는 확인한 계정을 대조하고 현재 세션 쿠키도 제거한다")
    void documentsDeactivation() throws Exception {
        mvc.perform(post(AccountDeactivationController.PATH).with(authentication(auth()))
                        .header("Origin", "https://baton.example").header("Sec-Fetch-Site", "same-origin").header("X-CSRF-TOKEN", "csrf")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedAccountId\":\"" + ACCOUNT + "\"}"))
                .andExpect(status().isNoContent()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(cookie().maxAge("JSESSIONID", 0))
                .andDo(MockMvcRestDocumentationWrapper.document("deactivateAccount", "현재 계정을 비활성화하고 모든 세션과 계정 권한 접근을 중지한다. 조직 기록은 보존하며 마지막 관리자는 먼저 후임 관리자를 지정해야 한다.", "계정 비활성화",
                        requestHeaders(headerWithName("Origin").description("BATON 동일 출처"), headerWithName("Sec-Fetch-Site").description("same-origin"), headerWithName("X-CSRF-TOKEN").description("세션 CSRF 토큰")),
                        requestFields(new ConstrainedFields(AccountDeactivationRequest.class).withPath("expectedAccountId").description("화면에서 확인한 로그인 계정")),
                        responseHeaders(headerWithName("Cache-Control").description("캐시 금지"), headerWithName("Set-Cookie").description("현재 JSESSIONID 제거"))));
        verify(useCase).deactivateAccount(ACCOUNT);
    }
    @Test @DisplayName("마지막 관리자의 비활성화는 팀 이름과 함께 충돌을 반환한다")
    void documentsLastAdministrator() throws Exception {
        doThrow(new AccountDeactivationBlockedException("운영 팀")).when(useCase).deactivateAccount(ACCOUNT);
        mvc.perform(post(AccountDeactivationController.PATH).with(authentication(auth()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedAccountId\":\"" + ACCOUNT + "\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ACCOUNT_DEACTIVATION_BLOCKED"))
                .andDo(MockMvcRestDocumentationWrapper.document("deactivateAccountLastAdministrator", "현재 계정을 비활성화하고 모든 세션과 계정 권한 접근을 중지한다. 조직 기록은 보존하며 마지막 관리자는 먼저 후임 관리자를 지정해야 한다.", "계정 비활성화",
                        responseFields(fieldWithPath("code").description("ACCOUNT_DEACTIVATION_BLOCKED"), fieldWithPath("message").description("후임 관리자를 지정할 팀 안내"))));
    }
    private UsernamePasswordAuthenticationToken auth() { return UsernamePasswordAuthenticationToken.authenticated(new Principal(ACCOUNT, 0), null, List.of()); }
    private record Principal(UUID accountId, long sessionVersion) implements AuthenticatedAccountPrincipal {}
}
