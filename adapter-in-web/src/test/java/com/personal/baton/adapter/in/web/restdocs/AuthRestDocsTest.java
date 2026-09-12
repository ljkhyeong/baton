package com.personal.baton.adapter.in.web.restdocs;

import static org.mockito.ArgumentMatchers.anyLong;

import com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper;
import com.epages.restdocs.apispec.ConstrainedFields;
import com.personal.baton.adapter.in.web.RequestIdFilter;
import com.personal.baton.adapter.in.web.auth.AuthController;
import com.personal.baton.adapter.in.web.auth.AccountSecurityController;
import com.personal.baton.adapter.in.web.auth.AuthExceptionHandler;
import com.personal.baton.adapter.in.web.auth.AuthRateLimiter;
import com.personal.baton.adapter.in.web.auth.AuthRequests;
import com.personal.baton.adapter.in.web.auth.AuthenticatedAccountPrincipal;
import com.personal.baton.adapter.in.web.config.AuthFeatureProperties;
import com.personal.baton.adapter.in.web.config.SocialLoginProviderCatalog;
import com.personal.baton.adapter.in.web.config.SecurityConfig;
import com.personal.baton.application.identity.port.in.ValidateAccountSessionUseCase;
import com.personal.baton.application.identity.AccountView;
import com.personal.baton.application.identity.error.CurrentPasswordMismatchException;
import com.personal.baton.application.identity.error.LocalPasswordUnavailableException;
import com.personal.baton.application.identity.port.in.AccountSecurityUseCase;
import com.personal.baton.application.identity.error.IdentityOperationUnavailableException;
import com.personal.baton.application.identity.error.HumanVerificationRejectedException;
import com.personal.baton.application.identity.port.in.LoadLocalCredentialUseCase;
import com.personal.baton.application.identity.port.in.LoadLocalCredentialUseCase.LocalCredentialResult;
import com.personal.baton.application.identity.port.in.RegisterLocalAccountUseCase;
import com.personal.baton.application.identity.port.in.HumanVerificationUseCase;
import com.personal.baton.application.identity.port.in.PasswordResetUseCase;
import com.personal.baton.application.identity.error.PasswordResetException;
import com.personal.baton.adapter.in.web.auth.AuthRequests.PasswordResetRequest;
import com.personal.baton.adapter.in.web.auth.AuthRequests.PasswordResetCompletionRequest;
import com.personal.baton.application.identity.port.in.VerifyLocalEmailUseCase;
import com.personal.baton.domain.identity.IdentityProvider;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.restdocs.RestDocumentationContextProvider;
import org.springframework.restdocs.RestDocumentationExtension;
import org.springframework.restdocs.payload.FieldDescriptor;
import org.springframework.restdocs.payload.JsonFieldType;
import org.springframework.restdocs.snippet.Snippet;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.security.web.method.annotation.CsrfTokenArgumentResolver;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.restdocs.headers.HeaderDocumentation.headerWithName;
import static org.springframework.restdocs.headers.HeaderDocumentation.requestHeaders;
import static org.springframework.restdocs.headers.HeaderDocumentation.responseHeaders;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.document;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.documentationConfiguration;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.get;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.post;
import static org.springframework.restdocs.operation.preprocess.Preprocessors.modifyHeaders;
import static org.springframework.restdocs.operation.preprocess.Preprocessors.prettyPrint;
import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;
import static org.springframework.restdocs.payload.PayloadDocumentation.requestFields;
import static org.springframework.restdocs.payload.PayloadDocumentation.responseFields;
import static org.springframework.restdocs.request.RequestDocumentation.formParameters;
import static org.springframework.restdocs.request.RequestDocumentation.parameterWithName;
import static org.springframework.restdocs.snippet.Attributes.key;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Tag("restdocs")
@ExtendWith(RestDocumentationExtension.class)
class AuthRestDocsTest {

    private static final String GET_SESSION_DESCRIPTION =
            "현재 BATON 브라우저 세션을 조회한다. 미인증과 인증 응답은 서로 다른 정확한 구조를 사용한다.";
    private static final String GET_SESSION_SUMMARY = "현재 인증 세션 조회";

    private static final UUID REQUEST_ID =
            UUID.fromString("11111111-2222-4333-8444-555555555555");
    private static final UUID ACCOUNT_ID =
            UUID.fromString("8e448211-66ae-44ab-9888-c4960648c22b");
    private static final String ORIGIN = "https://baton.example";
    private static final CsrfToken CSRF_TOKEN = new DefaultCsrfToken(
            "X-CSRF-TOKEN",
            "_csrf",
            "opaque-csrf-token"
    );

    private MockMvc mockMvc;
    private RegisterLocalAccountUseCase registerUseCase;
    private VerifyLocalEmailUseCase verifyUseCase;
    private PasswordResetUseCase resetUseCase;
    private HumanVerificationUseCase humanVerificationUseCase;

    @BeforeEach
    void setUp(RestDocumentationContextProvider restDocumentation) {
        registerUseCase = mock(RegisterLocalAccountUseCase.class);
        verifyUseCase = mock(VerifyLocalEmailUseCase.class);
        resetUseCase = mock(PasswordResetUseCase.class);
        humanVerificationUseCase = mock(HumanVerificationUseCase.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<SocialLoginProviderCatalog> registrations =
                mock(ObjectProvider.class);
        SocialLoginProviderCatalog providerCatalog =
                mock(SocialLoginProviderCatalog.class);
        when(providerCatalog.availableProviderIds()).thenReturn(List.of("google", "naver"));
        when(registrations.getIfAvailable()).thenReturn(providerCatalog);
        AuthController controller = new AuthController(
                registerUseCase,
                verifyUseCase,
                resetUseCase,
                registrations,
                new AuthRateLimiter(),
                new AuthFeatureProperties(true, true),
                humanVerificationUseCase
        );

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new AuthExceptionHandler())
                .setCustomArgumentResolvers(
                        new AuthenticationPrincipalArgumentResolver(),
                        new CsrfTokenArgumentResolver()
                )
                .addFilters(new RequestIdFilter(() -> REQUEST_ID))
                .addFilters(new SecurityContextHolderFilter(
                        new HttpSessionSecurityContextRepository()
                ))
                .apply(documentationConfiguration(restDocumentation)
                        .operationPreprocessors()
                        .withRequestDefaults(
                                prettyPrint(),
                                modifyHeaders().set(
                                        "X-CSRF-TOKEN",
                                        "opaque-csrf-token"
                                )
                        )
                        .withResponseDefaults(prettyPrint()))
                .alwaysExpect(header().string(
                        RequestIdFilter.HEADER_NAME,
                        REQUEST_ID.toString()
                ))
                .alwaysDo(document("{class-name}/{method-name}"))
                .build();
    }

    @DisplayName("CSRF 초기화 API는 변경 요청 헤더 이름과 일회용 토큰을 반환한다")
    @Test
    void documentsCsrfBootstrap() throws Exception {
        mockMvc.perform(get(AuthController.CSRF_PATH)
                        .requestAttr(CsrfToken.class.getName(), CSRF_TOKEN))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.csrfHeaderName").value("X-CSRF-TOKEN"))
                .andExpect(jsonPath("$.csrfToken").value("opaque-csrf-token"))
                .andDo(MockMvcRestDocumentationWrapper.document(
                        "getAuthCsrf",
                        "로그인·가입 등 쿠키 인증 변경 전에 사용할 CSRF 토큰을 준비한다.",
                        "인증 CSRF 토큰 준비",
                        responseHeaders(
                                headerWithName(RequestIdFilter.HEADER_NAME)
                                        .description("서버가 생성한 요청 추적 ID"),
                                headerWithName(HttpHeaders.CACHE_CONTROL)
                                        .description("민감 응답 캐시 금지")
                        ),
                        responseFields(
                                fieldWithPath("csrfHeaderName")
                                        .description("변경 요청에 사용할 CSRF 헤더 이름"),
                                fieldWithPath("csrfToken")
                                        .description("현재 브라우저 세션의 불투명 CSRF 토큰")
                        )));
    }

    @DisplayName("미인증 session API는 추가 필드 없이 authenticated false만 반환한다")
    @Test
    void documentsAnonymousSession() throws Exception {
        mockMvc.perform(get(AuthController.SESSION_PATH))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().json("{\"authenticated\":false}", true))
                .andDo(MockMvcRestDocumentationWrapper.document(
                        "getAuthSession",
                        GET_SESSION_DESCRIPTION,
                        GET_SESSION_SUMMARY,
                        responseHeaders(
                                headerWithName(RequestIdFilter.HEADER_NAME)
                                        .description("서버가 생성한 요청 추적 ID"),
                                headerWithName(HttpHeaders.CACHE_CONTROL)
                                        .description("민감 응답 캐시 금지")
                        ),
                        responseFields(
                                fieldWithPath("authenticated")
                                        .description("항상 false인 미인증 세션 표시")
                        )));
    }

    @DisplayName("로그인 상태 API는 기준 계정 UUID와 현재 CSRF 토큰을 반환한다")
    @Test
    void documentsAuthenticatedSession() throws Exception {
        TestAccountPrincipal principal = new TestAccountPrincipal(ACCOUNT_ID);
        UsernamePasswordAuthenticationToken authentication =
                UsernamePasswordAuthenticationToken.authenticated(
                        principal,
                        null,
                        List.of()
                );

        mockMvc.perform(get(AuthController.SESSION_PATH)
                        .with(authentication(authentication))
                        .requestAttr(CsrfToken.class.getName(), CSRF_TOKEN))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.accountId").value(ACCOUNT_ID.toString()))
                .andDo(MockMvcRestDocumentationWrapper.document(
                        "getAuthSessionAuthenticated",
                        GET_SESSION_DESCRIPTION,
                        GET_SESSION_SUMMARY,
                        responseHeaders(
                                headerWithName(RequestIdFilter.HEADER_NAME)
                                        .description("서버가 생성한 요청 추적 ID"),
                                headerWithName(HttpHeaders.CACHE_CONTROL)
                                        .description("민감 응답 캐시 금지")
                        ),
                        responseFields(
                                fieldWithPath("authenticated")
                                        .description("BATON 계정 세션 인증 여부"),
                                fieldWithPath("accountId")
                                        .description("공급자와 무관한 정규 BATON 계정 UUID"),
                                fieldWithPath("csrfHeaderName")
                                        .description("인증 변경에 사용할 CSRF 헤더 이름"),
                                fieldWithPath("csrfToken")
                                        .description("현재 브라우저 세션의 불투명 CSRF 토큰")
                        )));
    }

    @DisplayName("인증 공급자 목록 API는 설정이 끝난 허용 목록만 고정 순서로 반환한다")
    @Test
    void documentsAuthProviders() throws Exception {
        mockMvc.perform(get(AuthController.PROVIDERS_PATH))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.providers[0]").value("google"))
                .andExpect(jsonPath("$.providers[1]").value("naver"))
                .andExpect(jsonPath("$.localRegistrationEnabled").value(true))
                .andExpect(jsonPath("$.passwordResetEnabled").value(true))
                .andExpect(jsonPath("$.turnstileSiteKey").isEmpty())
                .andDo(MockMvcRestDocumentationWrapper.document(
                        "getAuthProviders",
                        "현재 서버에 완전히 구성된 로그인 공급자만 자격 증명 없이 조회한다.",
                        "로그인 공급자 목록 조회",
                        responseHeaders(
                                headerWithName(RequestIdFilter.HEADER_NAME)
                                        .description("서버가 생성한 요청 추적 ID"),
                                headerWithName(HttpHeaders.CACHE_CONTROL)
                                        .description("응답 캐시 금지")
                        ),
                        responseFields(
                                fieldWithPath("providers")
                                        .type(JsonFieldType.ARRAY)
                                        .attributes(key("itemsType").value("STRING"))
                                        .description("고정 순서의 로그인 공급자 식별자: google, naver"),
                                fieldWithPath("localRegistrationEnabled")
                                        .type(JsonFieldType.BOOLEAN)
                                        .description("새 자체 이메일 계정 등록 가능 여부"),
                                fieldWithPath("passwordResetEnabled")
                                        .type(JsonFieldType.BOOLEAN)
                                        .description("자체 이메일 계정의 비밀번호 재설정 메일 요청 가능 여부"),
                                fieldWithPath("turnstileSiteKey")
                                        .type(JsonFieldType.STRING)
                                        .optional()
                                        .description("자동 요청 방지 위젯의 공개 사이트 키. 기능이 꺼지면 null")
                        )));
    }

    @DisplayName("자체 이메일 등록 API는 계정 존재 여부를 숨긴 accepted 응답을 반환한다")
    @Test
    void documentsLocalRegistration() throws Exception {
        mockMvc.perform(sameOriginMutation(post(AuthController.LOCAL_REGISTRATIONS_PATH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "member@example.com",
                                  "displayName": "박민서",
                                  "turnstileToken": "verified-turnstile-token"
                                }
                                """))
                .andExpect(status().isAccepted())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.verificationRequired").value(true))
                .andDo(MockMvcRestDocumentationWrapper.document(
                        "registerLocalAccount",
                        "이메일 존재 여부를 노출하지 않고 자체 이메일 계정 등록과 검증 메일 발송을 요청한다.",
                        "자체 이메일 계정 등록",
                        sessionMutationHeaders(),
                        requestFields(
                                requestField(
                                        AuthRequests.LocalRegistrationRequest.class,
                                        "email",
                                        "등록할 이메일 주소"
                                ),
                                requestField(
                                        AuthRequests.LocalRegistrationRequest.class,
                                        "displayName",
                                        "BATON에 표시할 계정 이름"
                                ),
                                requestField(
                                        AuthRequests.LocalRegistrationRequest.class,
                                        "turnstileToken",
                                        "위젯이 발급한 일회용 자동 요청 방지 token"
                                ).optional()
                        ),
                        responseHeaders(
                                headerWithName(RequestIdFilter.HEADER_NAME)
                                        .description("서버가 생성한 요청 추적 ID"),
                                headerWithName(HttpHeaders.CACHE_CONTROL)
                                        .description("민감 응답 캐시 금지")
                        ),
                        responseFields(
                                fieldWithPath("verificationRequired")
                                        .description("이메일 검증이 필요한 일반화된 등록 결과")
                        )));
    }

    @DisplayName("자체 이메일 등록 API는 일시적인 신원 저장 실패를 재시도 가능한 503으로 반환한다")
    @Test
    void documentsLocalRegistrationTemporarilyUnavailable() throws Exception {
        when(registerUseCase.registerLocalAccount(any()))
                .thenThrow(identityOperationUnavailable());

        mockMvc.perform(sameOriginMutation(post(AuthController.LOCAL_REGISTRATIONS_PATH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "member@example.com",
                                  "displayName": "박민서",
                                  "turnstileToken": "verified-turnstile-token"
                                }
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value("IDENTITY_TEMPORARILY_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value(
                        "현재 인증 요청을 처리할 수 없습니다. 잠시 후 다시 시도해 주세요"
                ))
                .andDo(MockMvcRestDocumentationWrapper.document(
                        "registerLocalAccountTemporarilyUnavailable",
                        "이메일 존재 여부를 노출하지 않고 자체 이메일 계정 등록과 검증 메일 발송을 요청한다.",
                        "자체 이메일 계정 등록",
                        sessionMutationHeaders(),
                        requestFields(
                                requestField(
                                        AuthRequests.LocalRegistrationRequest.class,
                                        "email",
                                        "등록할 이메일 주소"
                                ),
                                requestField(
                                        AuthRequests.LocalRegistrationRequest.class,
                                        "displayName",
                                        "BATON에 표시할 계정 이름"
                                ),
                                requestField(
                                        AuthRequests.LocalRegistrationRequest.class,
                                        "turnstileToken",
                                        "위젯이 발급한 일회용 자동 요청 방지 token"
                                ).optional()
                        ),
                        errorResponseHeaders(),
                        errorResponseFields()
                ));
    }

    @DisplayName("자체 이메일 등록 API는 자동 요청 방지 실패를 안정적인 400으로 반환한다")
    @Test
    void documentsLocalRegistrationHumanVerificationFailure() throws Exception {
        doThrow(new HumanVerificationRejectedException())
                .when(humanVerificationUseCase).verify(any());

        mockMvc.perform(sameOriginMutation(post(AuthController.LOCAL_REGISTRATIONS_PATH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "member@example.com",
                                  "displayName": "박민서",
                                  "turnstileToken": "expired-turnstile-token"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value("HUMAN_VERIFICATION_FAILED"))
                .andExpect(jsonPath("$.message").value("자동 요청 방지 확인을 다시 완료해 주세요"))
                .andDo(MockMvcRestDocumentationWrapper.document(
                        "registerLocalAccountHumanVerificationFailed",
                        "이메일 존재 여부를 노출하지 않고 자체 이메일 계정 등록과 검증 메일 발송을 요청한다.",
                        "자체 이메일 계정 등록",
                        sessionMutationHeaders(),
                        requestFields(
                                requestField(AuthRequests.LocalRegistrationRequest.class, "email", "등록할 이메일 주소"),
                                requestField(
                                        AuthRequests.LocalRegistrationRequest.class,
                                        "displayName",
                                        "BATON에 표시할 계정 이름"
                                ),
                                requestField(
                                        AuthRequests.LocalRegistrationRequest.class,
                                        "turnstileToken",
                                        "위젯이 발급한 일회용 자동 요청 방지 token"
                                ).optional()
                        ),
                        errorResponseHeaders(),
                        errorResponseFields()
                ));
    }

    @DisplayName("자체 이메일 검증 API는 일회용 토큰으로 최초 로그인 정보를 만든다")
    @Test
    void documentsLocalEmailVerification() throws Exception {
        mockMvc.perform(sameOriginMutation(post(AuthController.LOCAL_EMAIL_VERIFICATIONS_PATH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "token": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                                  "password": "correct horse battery staple"
                                }
                                """))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andDo(MockMvcRestDocumentationWrapper.document(
                        "verifyLocalEmail",
                        "일회성 이메일 검증 토큰을 소비하고 검증된 자체 이메일 계정의 최초 비밀번호 자격 증명을 만든다.",
                        "자체 이메일 검증과 자격 증명 생성",
                        sessionMutationHeaders(),
                        requestFields(
                                requestField(
                                        AuthRequests.LocalEmailVerificationRequest.class,
                                        "token",
                                        "메일 프래그먼트에서 전달한 일회성 검증 토큰"
                                ),
                                requestField(
                                        AuthRequests.LocalEmailVerificationRequest.class,
                                        "password",
                                        "최초 로그인에 사용할 비밀번호"
                                )
                        ),
                        responseHeaders(
                                headerWithName(RequestIdFilter.HEADER_NAME)
                                        .description("서버가 생성한 요청 추적 ID"),
                                headerWithName(HttpHeaders.CACHE_CONTROL)
                                        .description("민감 응답 캐시 금지")
                        )));
    }

    @DisplayName("자체 이메일 검증 API는 일시적인 신원 저장 실패를 재시도 가능한 503으로 반환한다")
    @Test
    void documentsLocalEmailVerificationTemporarilyUnavailable() throws Exception {
        when(verifyUseCase.verifyLocalEmail(any()))
                .thenThrow(identityOperationUnavailable());

        mockMvc.perform(sameOriginMutation(post(AuthController.LOCAL_EMAIL_VERIFICATIONS_PATH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "token": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                                  "password": "correct horse battery staple"
                                }
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value("IDENTITY_TEMPORARILY_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value(
                        "현재 인증 요청을 처리할 수 없습니다. 잠시 후 다시 시도해 주세요"
                ))
                .andDo(MockMvcRestDocumentationWrapper.document(
                        "verifyLocalEmailTemporarilyUnavailable",
                        "일회성 이메일 검증 토큰을 소비하고 검증된 자체 이메일 계정의 최초 비밀번호 자격 증명을 만든다.",
                        "자체 이메일 검증과 자격 증명 생성",
                        sessionMutationHeaders(),
                        requestFields(
                                requestField(
                                        AuthRequests.LocalEmailVerificationRequest.class,
                                        "token",
                                        "메일 프래그먼트에서 전달한 일회성 검증 토큰"
                                ),
                                requestField(
                                        AuthRequests.LocalEmailVerificationRequest.class,
                                        "password",
                                        "최초 로그인에 사용할 비밀번호"
                                )
                        ),
                        errorResponseHeaders(),
                        errorResponseFields()
                ));
    }

    @ParameterizedTest
    @CsvSource({
            "registerLocalAccount,local/registrations,503",
            "requestPasswordReset,local/password-reset-requests,400",
            "requestPasswordReset,local/password-reset-requests,503"
    })
    @DisplayName("메일 요청의 검증 실패와 공급자 장애 계약을 문서화한다")
    void documentsHumanVerificationErrors(String operation, String path, int statusCode) throws Exception {
        boolean unavailable = statusCode == 503;
        RuntimeException failure = unavailable
                ? new com.personal.baton.application.identity.error.HumanVerificationUnavailableException()
                : new HumanVerificationRejectedException();
        doThrow(failure).when(humanVerificationUseCase).verify(any());
        mockMvc.perform(sameOriginMutation(post("/api/v1/auth/" + path))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"member@example.com\",\"displayName\":\"사용자\",\"turnstileToken\":\"test-token\"}"))
                .andExpect(status().is(statusCode))
                .andExpect(jsonPath("$.code").value(unavailable
                        ? "HUMAN_VERIFICATION_UNAVAILABLE" : "HUMAN_VERIFICATION_FAILED"))
                .andDo(MockMvcRestDocumentationWrapper.document(operation + "HumanVerification" + statusCode,
                        operation.equals("registerLocalAccount")
                                ? "이메일 존재 여부를 노출하지 않고 자체 이메일 계정 등록과 검증 메일 발송을 요청한다."
                                : "인증된 자체 이메일 계정에 30분짜리 일회용 재설정 링크를 보낸다. 계정 존재 여부는 응답하지 않는다.",
                        operation.equals("registerLocalAccount") ? "자체 이메일 계정 등록" : "비밀번호 재설정 메일 요청",
                        sessionMutationHeaders(), errorResponseHeaders(), errorResponseFields()));
    }

    private MockHttpServletRequestBuilder sameOriginMutation(
            MockHttpServletRequestBuilder request
    ) {
        return request
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .header("Sec-Fetch-Site", "same-origin")
                .header(CSRF_TOKEN.getHeaderName(), CSRF_TOKEN.getToken());
    }

    @Test
    @DisplayName("재설정 메일 요청은 계정 존재 여부와 관계없이 동일한 접수 응답을 반환한다")
    void documentsPasswordResetRequest() throws Exception {
        mockMvc.perform(sameOriginMutation(post(AuthController.PASSWORD_RESET_REQUESTS_PATH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"member@example.com\",\"turnstileToken\":\"verified-turnstile-token\"}"))
                .andExpect(status().isAccepted())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().json("{\"accepted\":true}", true))
                .andDo(MockMvcRestDocumentationWrapper.document(
                        "requestPasswordReset", "인증된 자체 이메일 계정에 30분짜리 일회용 재설정 링크를 보낸다. 계정 존재 여부는 응답하지 않는다.",
                        "비밀번호 재설정 메일 요청", sessionMutationHeaders(), errorResponseHeaders(),
                        requestFields(
                                requestField(PasswordResetRequest.class, "email", "가입한 이메일"),
                                requestField(
                                        PasswordResetRequest.class,
                                        "turnstileToken",
                                        "위젯이 발급한 일회용 자동 요청 방지 token"
                                ).optional()
                        ),
                        responseFields(fieldWithPath("accepted").description("항상 true인 요청 접수 표시. 계정 존재나 실제 발송 완료를 뜻하지 않는다"))));
    }

    @Test
    @DisplayName("비밀번호 재설정 완료는 세션이나 계정 정보를 반환하지 않는다")
    void documentsPasswordResetCompletion() throws Exception {
        mockMvc.perform(sameOriginMutation(post(AuthController.PASSWORD_RESETS_PATH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"password\":\"new correct horse battery staple\"}"))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().string(""))
                .andDo(MockMvcRestDocumentationWrapper.document(
                        "resetPassword", "재설정 토큰을 소비하고 비밀번호와 계정 세션 버전을 변경한다. 기존 세션은 다음 요청부터 거부하며 자동 로그인하지 않는다.",
                        "비밀번호 재설정 완료", sessionMutationHeaders(), errorResponseHeaders(),
                        requestFields(
                                requestField(PasswordResetCompletionRequest.class, "token", "메일 링크에서 읽은 일회용 재설정 토큰"),
                                requestField(PasswordResetCompletionRequest.class, "password", "새 비밀번호"))));
    }

    @Test
    @DisplayName("만료되거나 사용한 재설정 토큰은 안정적인 오류 코드로 응답한다")
    void documentsInvalidPasswordResetToken() throws Exception {
        doThrow(new PasswordResetException()).when(resetUseCase).resetPassword(any());
        mockMvc.perform(sameOriginMutation(post(AuthController.PASSWORD_RESETS_PATH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"password\":\"new correct horse battery staple\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value("PASSWORD_RESET_INVALID"))
                .andDo(MockMvcRestDocumentationWrapper.document(
                        "resetPasswordInvalidToken", "재설정 토큰을 소비하고 비밀번호와 계정 세션 버전을 변경한다. 기존 세션은 다음 요청부터 거부하며 자동 로그인하지 않는다.",
                        "비밀번호 재설정 완료", errorResponseHeaders(), errorResponseFields()));
    }

    private org.springframework.restdocs.snippet.Snippet sessionMutationHeaders() {
        return requestHeaders(
                headerWithName(HttpHeaders.ORIGIN)
                        .description("BATON 공개 출처와 정확히 같은 브라우저 출처"),
                headerWithName("Sec-Fetch-Site")
                        .description("브라우저가 보낸 same-origin Fetch Metadata"),
                headerWithName(CSRF_TOKEN.getHeaderName())
                        .description("GET /api/v1/auth/csrf에서 받은 동적 CSRF 토큰")
        );
    }

    private Snippet errorResponseHeaders() {
        return responseHeaders(
                headerWithName(RequestIdFilter.HEADER_NAME)
                        .description("서버가 생성한 요청 추적 ID"),
                headerWithName(HttpHeaders.CACHE_CONTROL)
                        .description("민감 응답 캐시 금지")
        );
    }

    private Snippet errorResponseFields() {
        return responseFields(
                fieldWithPath("code").description("안정적인 오류 코드"),
                fieldWithPath("message").description("재시도를 안내하는 안전한 오류 설명")
        );
    }

    private IdentityOperationUnavailableException identityOperationUnavailable() {
        return new IdentityOperationUnavailableException(
                "identity persistence is temporarily unavailable",
                new RuntimeException("test transient persistence failure")
        );
    }

    private FieldDescriptor requestField(
            Class<?> requestType,
            String path,
            String description
    ) {
        return new ConstrainedFields(requestType).withPath(path).description(description);
    }

    private record TestAccountPrincipal(UUID accountId)
            implements AuthenticatedAccountPrincipal {
        @Override
        public long sessionVersion() {
            return 0;
        }
    }
}

@Tag("restdocs")
@ExtendWith(RestDocumentationExtension.class)
@WebMvcTest(
        controllers = {AuthController.class, AccountSecurityController.class},
        properties = "baton.auth.local-registration-enabled=true"
)
@Import({
        SecurityConfig.class,
        AuthSessionRestDocsTest.PasswordEncoderTestConfig.class
})
class AuthSessionRestDocsTest {

    @MockitoBean
    private ValidateAccountSessionUseCase validateAccountSessionUseCase;

    @MockitoBean
    private HumanVerificationUseCase humanVerificationUseCase;

    @BeforeEach
    void acceptCurrentAccountSessions() {
        when(validateAccountSessionUseCase.isAccountSessionCurrent(any(), anyLong())).thenReturn(true);
    }

    private static final UUID REQUEST_ID =
            UUID.fromString("11111111-2222-4333-8444-555555555555");
    private static final UUID ACCOUNT_ID =
            UUID.fromString("8e448211-66ae-44ab-9888-c4960648c22b");
    private static final String EMAIL = "member@example.com";
    private static final String PASSWORD = "correct horse battery staple";
    private static final String ORIGIN = "http://localhost:8080";

    @Autowired
    private WebApplicationContext applicationContext;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private RegisterLocalAccountUseCase registerLocalAccountUseCase;

    @MockitoBean
    private VerifyLocalEmailUseCase verifyLocalEmailUseCase;

    @MockitoBean
    private PasswordResetUseCase passwordResetUseCase;

    @MockitoBean
    private AccountSecurityUseCase accountSecurityUseCase;

    @MockitoBean
    private LoadLocalCredentialUseCase loadLocalCredentialUseCase;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp(RestDocumentationContextProvider restDocumentation) {
        mockMvc = MockMvcBuilders.webAppContextSetup(applicationContext)
                .addFilters(new RequestIdFilter(() -> REQUEST_ID))
                .apply(springSecurity())
                .apply(documentationConfiguration(restDocumentation)
                        .operationPreprocessors()
                        .withRequestDefaults(
                                prettyPrint(),
                                modifyHeaders().set(
                                        "X-CSRF-TOKEN",
                                        "opaque-csrf-token"
                                )
                        )
                        .withResponseDefaults(prettyPrint()))
                .alwaysExpect(header().string(
                        RequestIdFilter.HEADER_NAME,
                        REQUEST_ID.toString()
                ))
                .alwaysDo(document("{class-name}/{method-name}"))
                .build();
    }

    @DisplayName("자체 이메일 로그인 API는 실제 보안 필터에서 account session을 만든다")
    @Test
    void documentsLocalSessionCreation() throws Exception {
        when(loadLocalCredentialUseCase.loadLocalCredential(EMAIL))
                .thenReturn(Optional.of(new LocalCredentialResult(
                        ACCOUNT_ID,
                        passwordEncoder.encode(PASSWORD),
                        true, 0
                )));

        mockMvc.perform(sameOriginMutation(post(AuthController.LOCAL_SESSION_PATH))
                        .with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("email", EMAIL)
                        .param("password", PASSWORD))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andDo(MockMvcRestDocumentationWrapper.document(
                        "createLocalAuthSession",
                        "검증된 자체 이메일 자격 증명을 확인하고 세션 고정 공격 방지를 적용한 BATON 계정 세션을 만든다.",
                        "자체 이메일 계정 세션 생성",
                        sessionMutationHeaders(),
                        formParameters(
                                parameterWithName("email")
                                        .description("검증된 자체 계정 이메일 주소"),
                                parameterWithName("password")
                                        .description("자체 계정 비밀번호")
                        ),
                        noStoreResponseHeaders()
                ));
    }

    @DisplayName("자체 이메일 로그인 API는 일시적인 신원 인프라 장애를 503으로 반환한다")
    @Test
    void documentsLocalSessionTemporarilyUnavailable() throws Exception {
        when(loadLocalCredentialUseCase.loadLocalCredential(EMAIL))
                .thenThrow(new IdentityOperationUnavailableException(
                        "identity persistence is temporarily unavailable",
                        new RuntimeException("test transient persistence failure")
                ));

        mockMvc.perform(sameOriginMutation(post(AuthController.LOCAL_SESSION_PATH))
                        .with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("email", EMAIL)
                        .param("password", PASSWORD))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code")
                        .value("IDENTITY_TEMPORARILY_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value(
                        "현재 인증 요청을 처리할 수 없습니다. 잠시 후 다시 시도해 주세요"
                ))
                .andDo(MockMvcRestDocumentationWrapper.document(
                        "createLocalAuthSessionTemporarilyUnavailable",
                        "검증된 자체 이메일 자격 증명을 확인하고 세션 고정 공격 방지를 적용한 BATON 계정 세션을 만든다.",
                        "자체 이메일 계정 세션 생성",
                        sessionMutationHeaders(),
                        formParameters(
                                parameterWithName("email")
                                        .description("검증된 자체 계정 이메일 주소"),
                                parameterWithName("password")
                                        .description("자체 계정 비밀번호")
                        ),
                        noStoreResponseHeaders(),
                        responseFields(
                                fieldWithPath("code")
                                        .description("안정적인 오류 코드"),
                                fieldWithPath("message")
                                        .description("재시도를 안내하는 안전한 오류 설명")
                        )
                ));
    }

    @DisplayName("logout API는 실제 보안 필터에서 현재 session과 JSESSIONID를 무효화한다")
    @Test
    void documentsLogout() throws Exception {
        MockHttpSession session = authenticatedSession();

        mockMvc.perform(sameOriginMutation(post(AuthController.LOGOUT_PATH))
                        .session(session)
                        .with(csrf().asHeader()))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(
                        HttpHeaders.SET_COOKIE,
                        containsString("JSESSIONID=")
                ))
                .andDo(MockMvcRestDocumentationWrapper.document(
                        "deleteAuthSession",
                        "현재 BATON 계정 세션을 종료하고 서버 세션과 브라우저 JSESSIONID를 무효화한다.",
                        "현재 계정 세션 종료",
                        sessionMutationHeaders(),
                        responseHeaders(
                                headerWithName(RequestIdFilter.HEADER_NAME)
                                        .description("서버가 생성한 요청 추적 ID"),
                                headerWithName(HttpHeaders.CACHE_CONTROL)
                                        .description("민감 응답 캐시 금지"),
                                headerWithName(HttpHeaders.SET_COOKIE)
                                        .description("기존 JSESSIONID를 즉시 만료하는 쿠키")
                        )
                ));

        assertThat(session.isInvalid()).isTrue();
    }

    @DisplayName("계정 보안 조회 API는 표시 이름과 연결된 로그인 방법을 반환한다")
    @Test
    void documentsAccountSecurity() throws Exception {
        when(accountSecurityUseCase.getAccount(ACCOUNT_ID)).thenReturn(new AccountView(
                ACCOUNT_ID,
                "박민서",
                List.of(
                        new AccountView.LinkedIdentityView(
                                IdentityProvider.GOOGLE,
                                null,
                                false
                        ),
                        new AccountView.LinkedIdentityView(
                                IdentityProvider.LOCAL_EMAIL,
                                EMAIL,
                                true
                        )
                ),
                0
        ));

        mockMvc.perform(get(AccountSecurityController.ACCOUNT_PATH)
                        .session(authenticatedSession()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.accountId").value(ACCOUNT_ID.toString()))
                .andExpect(jsonPath("$.displayName").value("박민서"))
                .andExpect(jsonPath("$.identities[0].provider").value("google"))
                .andExpect(jsonPath("$.identities[0].email").value((String) null))
                .andExpect(jsonPath("$.identities[1].provider").value("local_email"))
                .andExpect(jsonPath("$.identities[1].email").value(EMAIL))
                .andDo(MockMvcRestDocumentationWrapper.document(
                        "getAccountSecurity",
                        "현재 로그인 계정의 표시 이름과 연결된 로그인 방법을 조회한다. 공급자가 이메일을 제공하지 않으면 email은 null이다.",
                        "계정 보안 정보 조회",
                        noStoreResponseHeaders(),
                        responseFields(
                                fieldWithPath("accountId")
                                        .description("공급자와 무관한 BATON 계정 UUID"),
                                fieldWithPath("displayName")
                                        .description("계정 표시 이름"),
                                fieldWithPath("identities")
                                        .type(JsonFieldType.ARRAY)
                                        .attributes(key("itemsType").value("OBJECT"))
                                        .description("연결된 로그인 방법 목록"),
                                fieldWithPath("identities[].provider")
                                        .description("로그인 방법: google, naver, local_email"),
                                fieldWithPath("identities[].email")
                                        .optional()
                                        .description("공급자가 제공한 이메일. 제공하지 않으면 null"),
                                fieldWithPath("identities[].emailVerified")
                                        .description("해당 로그인 방법에서 확인한 이메일 여부")
                        )
                ));
    }

    @DisplayName("현재 비밀번호 변경 API는 모든 기존 세션과 현재 세션을 종료한다")
    @Test
    void documentsLocalPasswordChange() throws Exception {
        MockHttpSession session = authenticatedSession();

        mockMvc.perform(sameOriginMutation(post(
                                AccountSecurityController.LOCAL_PASSWORD_CHANGES_PATH
                        ))
                        .session(session)
                        .with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "currentPassword": "correct horse battery staple",
                                  "newPassword": "new correct horse battery staple"
                                }
                                """))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(
                        HttpHeaders.SET_COOKIE,
                        containsString("JSESSIONID=")
                ))
                .andDo(MockMvcRestDocumentationWrapper.document(
                        "changeLocalPassword",
                        "현재 자체 이메일 비밀번호를 확인해 새 비밀번호로 바꾸고 계정의 모든 기존 세션을 종료한다. 진행 중인 재설정 링크도 무효화한다.",
                        "자체 이메일 비밀번호 변경",
                        sessionMutationHeaders(),
                        requestFields(
                                requestField(
                                        AuthRequests.LocalPasswordChangeRequest.class,
                                        "currentPassword",
                                        "현재 자체 이메일 비밀번호"
                                ),
                                requestField(
                                        AuthRequests.LocalPasswordChangeRequest.class,
                                        "newPassword",
                                        "새 비밀번호"
                                )
                        ),
                        responseHeaders(
                                headerWithName(RequestIdFilter.HEADER_NAME)
                                        .description("서버가 생성한 요청 추적 ID"),
                                headerWithName(HttpHeaders.CACHE_CONTROL)
                                        .description("민감 응답 캐시 금지"),
                                headerWithName(HttpHeaders.SET_COOKIE)
                                        .description("현재 JSESSIONID를 즉시 만료하는 쿠키")
                        )
                ));

        assertThat(session.isInvalid()).isTrue();
        verify(accountSecurityUseCase).changeLocalPassword(any());
    }

    @DisplayName("현재 비밀번호가 다르면 세션을 유지하고 안정적인 오류로 응답한다")
    @Test
    void documentsInvalidCurrentPassword() throws Exception {
        doThrow(new CurrentPasswordMismatchException())
                .when(accountSecurityUseCase)
                .changeLocalPassword(any());
        MockHttpSession session = authenticatedSession();

        mockMvc.perform(sameOriginMutation(post(
                                AccountSecurityController.LOCAL_PASSWORD_CHANGES_PATH
                        ))
                        .session(session)
                        .with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "currentPassword": "wrong current password",
                                  "newPassword": "new correct horse battery staple"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value("CURRENT_PASSWORD_INVALID"))
                .andDo(MockMvcRestDocumentationWrapper.document(
                        "changeLocalPasswordInvalidCurrentPassword",
                        "현재 자체 이메일 비밀번호를 확인해 새 비밀번호로 바꾸고 계정의 모든 기존 세션을 종료한다. 진행 중인 재설정 링크도 무효화한다.",
                        "자체 이메일 비밀번호 변경",
                        errorResponseHeaders(),
                        errorResponseFields()
                ));

        assertThat(session.isInvalid()).isFalse();
    }

    @DisplayName("소셜 로그인 전용 계정의 비밀번호 변경 요청은 충돌로 응답한다")
    @Test
    void documentsUnavailableLocalPassword() throws Exception {
        doThrow(new LocalPasswordUnavailableException())
                .when(accountSecurityUseCase)
                .changeLocalPassword(any());

        mockMvc.perform(sameOriginMutation(post(
                                AccountSecurityController.LOCAL_PASSWORD_CHANGES_PATH
                        ))
                        .session(authenticatedSession())
                        .with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "currentPassword": "correct horse battery staple",
                                  "newPassword": "new correct horse battery staple"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOCAL_PASSWORD_UNAVAILABLE"))
                .andDo(MockMvcRestDocumentationWrapper.document(
                        "changeLocalPasswordUnavailable",
                        "현재 자체 이메일 비밀번호를 확인해 새 비밀번호로 바꾸고 계정의 모든 기존 세션을 종료한다. 진행 중인 재설정 링크도 무효화한다.",
                        "자체 이메일 비밀번호 변경",
                        errorResponseHeaders(),
                        errorResponseFields()
                ));
    }

    @DisplayName("전체 세션 종료 API는 계정 세션 버전을 바꾸고 현재 세션도 종료한다")
    @Test
    void documentsAccountSessionRevocation() throws Exception {
        MockHttpSession session = authenticatedSession();

        mockMvc.perform(sameOriginMutation(post(
                                AccountSecurityController.SESSION_REVOCATIONS_PATH
                        ))
                        .session(session)
                        .with(csrf().asHeader()))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(
                        HttpHeaders.SET_COOKIE,
                        containsString("JSESSIONID=")
                ))
                .andDo(MockMvcRestDocumentationWrapper.document(
                        "revokeAccountSessions",
                        "계정의 세션 버전을 변경해 모든 브라우저의 기존 계정 세션을 종료하고 현재 JSESSIONID도 즉시 무효화한다.",
                        "모든 계정 세션 종료",
                        sessionMutationHeaders(),
                        responseHeaders(
                                headerWithName(RequestIdFilter.HEADER_NAME)
                                        .description("서버가 생성한 요청 추적 ID"),
                                headerWithName(HttpHeaders.CACHE_CONTROL)
                                        .description("민감 응답 캐시 금지"),
                                headerWithName(HttpHeaders.SET_COOKIE)
                                        .description("현재 JSESSIONID를 즉시 만료하는 쿠키")
                        )
                ));

        assertThat(session.isInvalid()).isTrue();
        verify(accountSecurityUseCase).revokeAllSessions(ACCOUNT_ID);
    }

    private MockHttpServletRequestBuilder sameOriginMutation(
            MockHttpServletRequestBuilder request
    ) {
        return request
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .header("Sec-Fetch-Site", "same-origin");
    }

    private MockHttpSession authenticatedSession() {
        TestAccountPrincipal principal = new TestAccountPrincipal(ACCOUNT_ID);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                principal,
                null,
                List.of()
        ));
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                context
        );
        return session;
    }

    private Snippet sessionMutationHeaders() {
        return requestHeaders(
                headerWithName(HttpHeaders.ORIGIN)
                        .description("BATON 공개 출처와 정확히 같은 브라우저 출처"),
                headerWithName("Sec-Fetch-Site")
                        .description("브라우저가 보낸 same-origin Fetch Metadata"),
                headerWithName("X-CSRF-TOKEN")
                        .description("GET /api/v1/auth/csrf에서 받은 동적 CSRF 토큰")
        );
    }

    private Snippet noStoreResponseHeaders() {
        return responseHeaders(
                headerWithName(RequestIdFilter.HEADER_NAME)
                        .description("서버가 생성한 요청 추적 ID"),
                headerWithName(HttpHeaders.CACHE_CONTROL)
                        .description("민감 응답 캐시 금지")
        );
    }

    private Snippet errorResponseHeaders() {
        return noStoreResponseHeaders();
    }

    private Snippet errorResponseFields() {
        return responseFields(
                fieldWithPath("code").description("안정적인 오류 코드"),
                fieldWithPath("message").description("사용자가 다음 행동을 판단할 수 있는 오류 설명")
        );
    }

    private FieldDescriptor requestField(
            Class<?> requestType,
            String path,
            String description
    ) {
        return new ConstrainedFields(requestType).withPath(path).description(description);
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
