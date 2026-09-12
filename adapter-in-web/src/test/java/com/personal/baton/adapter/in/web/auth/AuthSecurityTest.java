package com.personal.baton.adapter.in.web.auth;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.personal.baton.adapter.in.web.RequestIdFilter;
import com.personal.baton.adapter.in.web.config.SecurityConfig;
import com.personal.baton.application.identity.port.in.ValidateAccountSessionUseCase;
import com.personal.baton.application.identity.port.in.AccountSecurityUseCase;
import com.personal.baton.adapter.in.web.config.WebFilterConfig;
import com.personal.baton.application.identity.error.EmailVerificationException;
import com.personal.baton.application.identity.error.EmailVerificationDeliveryUnavailableException;
import com.personal.baton.application.identity.error.EmailVerificationPayloadProtectionException;
import com.personal.baton.application.identity.error.IdentityConflictException;
import com.personal.baton.application.identity.error.IdentityOperationUnavailableException;
import com.personal.baton.application.identity.port.in.LoadLocalCredentialUseCase;
import com.personal.baton.application.identity.port.in.HumanVerificationUseCase;
import com.personal.baton.application.identity.port.in.LoadLocalCredentialUseCase.LocalCredentialResult;
import com.personal.baton.application.identity.port.in.RegisterLocalAccountUseCase;
import com.personal.baton.application.identity.port.in.PasswordResetUseCase;
import com.personal.baton.application.identity.port.in.UpdateLocalCredentialPasswordUseCase;
import com.personal.baton.application.identity.port.in.UpdateLocalCredentialPasswordUseCase.UpdateLocalCredentialPasswordCommand;
import com.personal.baton.application.identity.port.in.VerifyLocalEmailUseCase;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.test.web.support.WebTestUtils;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = {AuthController.class, AccountSecurityController.class},
        properties = {"baton.auth.local-registration-enabled=true", "baton.auth.password-reset-enabled=true"}
)
@Import({
        SecurityConfig.class,
        WebFilterConfig.class,
        AuthSecurityTest.PasswordEncoderTestConfig.class
})
class AuthSecurityTest {

    @MockitoBean
    private ValidateAccountSessionUseCase validateAccountSessionUseCase;

    @MockitoBean
    private HumanVerificationUseCase humanVerificationUseCase;

    private static final UUID ACCOUNT_ID =
            UUID.fromString("8e448211-66ae-44ab-9888-c4960648c22b");
    private static final String EMAIL = "member@example.com";
    private static final String PASSWORD = "correct horse battery staple";
    private static final String ORIGIN = "http://localhost";
    private static final String REQUEST_ID_MDC_KEY = "requestId";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private WebApplicationContext applicationContext;

    @Autowired
    private CsrfTokenRepository csrfTokenRepository;

    @MockitoBean
    private RegisterLocalAccountUseCase registerLocalAccountUseCase;

    @MockitoBean
    private VerifyLocalEmailUseCase verifyLocalEmailUseCase;

    @MockitoBean
    private PasswordResetUseCase passwordResetUseCase;

    @MockitoBean
    private LoadLocalCredentialUseCase loadLocalCredentialUseCase;

    @MockitoBean
    private UpdateLocalCredentialPasswordUseCase updateLocalCredentialPasswordUseCase;

    @MockitoBean
    private AccountSecurityUseCase accountSecurityUseCase;

    @BeforeEach
    void restoreConfiguredCsrfRepository() {
        when(validateAccountSessionUseCase.isAccountSessionCurrent(any(), anyLong()))
                .thenReturn(true);
        MockHttpServletRequest request = new MockHttpServletRequest(
                applicationContext.getServletContext()
        );
        request.setMethod("GET");
        request.setRequestURI(AuthController.CSRF_PATH);
        WebTestUtils.setCsrfTokenRepository(request, csrfTokenRepository);
    }

    @DisplayName("CSRF 초기화는 세션 없이 쿠키와 헤더용 불투명 토큰을 반환한다")
    @Test
    void exposesCsrfBootstrapContract() throws Exception {
        MvcResult result = mockMvc.perform(get(AuthController.CSRF_PATH))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.csrfHeaderName").value("X-CSRF-TOKEN"))
                .andExpect(jsonPath("$.csrfToken").isNotEmpty())
                .andReturn();

        assertThat(result.getRequest().getSession(false)).isNull();
        assertThat(result.getResponse().getCookie("XSRF-TOKEN"))
                .isNotNull()
                .satisfies(cookie -> {
                    assertThat(cookie.isHttpOnly()).isTrue();
                    assertThat(cookie.getPath()).isEqualTo("/");
                    assertThat(cookie.getAttribute("SameSite")).isEqualTo("Lax");
                });
    }

    @DisplayName("쿠키 기반 CSRF 초기화 토큰은 다음 SPA 변경 요청에서 그대로 검증된다")
    @Test
    void acceptsMutationWithCookieBackedCsrfToken() throws Exception {
        MvcResult bootstrap = mockMvc.perform(get(AuthController.CSRF_PATH))
                .andExpect(status().isOk())
                .andReturn();
        String csrfToken = objectMapper.readTree(bootstrap.getResponse().getContentAsString())
                .get("csrfToken")
                .asText();
        var csrfCookie = bootstrap.getResponse().getCookie("XSRF-TOKEN");
        assertThat(csrfCookie)
                .as("response headers=%s", bootstrap.getResponse().getHeaderNames())
                .isNotNull();

        mockMvc.perform(post(AuthController.LOCAL_REGISTRATIONS_PATH)
                        .cookie(csrfCookie)
                        .header("X-CSRF-TOKEN", csrfToken)
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .header("Sec-Fetch-Site", "same-origin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRegistration().replace(
                                EMAIL,
                                "csrf-cookie@example.com"
                        )))
                .andExpect(status().isAccepted());

        verify(registerLocalAccountUseCase).registerLocalAccount(any());
    }

    @DisplayName("미인증 session 조회는 session을 만들지 않고 정확히 authenticated false만 반환한다")
    @Test
    void exposesAnonymousSessionWithoutCreatingSession() throws Exception {
        MvcResult result = mockMvc.perform(get(AuthController.SESSION_PATH))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
                .andExpect(content().json("{\"authenticated\":false}", true))
                .andReturn();

        assertThat(result.getRequest().getSession(false)).isNull();
    }

    @DisplayName("계정 보안 조회는 로그인하지 않은 요청을 application port 전에 거부한다")
    @Test
    void requiresAccountSessionForAccountSecurityRead() throws Exception {
        mockMvc.perform(get(AccountSecurityController.ACCOUNT_PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().json("""
                        {
                          "code": "AUTHENTICATION_REQUIRED",
                          "message": "BATON 계정 로그인이 필요합니다"
                        }
                        """, true));

        verifyNoInteractions(accountSecurityUseCase);
    }

    @DisplayName("비밀번호 변경은 유효한 세션이 있어도 다른 출처 요청을 거부한다")
    @Test
    void rejectsCrossOriginPasswordChange() throws Exception {
        mockMvc.perform(post(AccountSecurityController.LOCAL_PASSWORD_CHANGES_PATH)
                        .with(csrf())
                        .header(HttpHeaders.ORIGIN, "https://attacker.example")
                        .header("Sec-Fetch-Site", "cross-site")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "currentPassword": "correct horse battery staple",
                                  "newPassword": "new correct horse battery staple"
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ORIGIN_DENIED"));

        verifyNoInteractions(accountSecurityUseCase);
    }

    @DisplayName("설정하지 않은 소셜 공급자 목록은 가입 가능 여부와 인증 정보가 없는 빈 배열만 반환한다")
    @Test
    void exposesEmptyProviderListWhenSocialLoginIsUnavailable() throws Exception {
        mockMvc.perform(get(AuthController.PROVIDERS_PATH))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().json(
                        "{\"providers\":[],\"localRegistrationEnabled\":true,\"passwordResetEnabled\":true,\"turnstileSiteKey\":null}",
                        true
                ));
    }

    @DisplayName("로컬 가입은 CSRF 토큰이 없으면 애플리케이션 포트 호출 전에 거부한다")
    @Test
    void rejectsRegistrationWithoutCsrf() throws Exception {
        mockMvc.perform(post(AuthController.LOCAL_REGISTRATIONS_PATH)
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .header("Sec-Fetch-Site", "same-origin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRegistration()))
                .andExpect(status().isForbidden())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "code": "REQUEST_FORBIDDEN",
                          "message": "요청을 허용할 수 없습니다"
                        }
                        """, true));

        verifyNoInteractions(registerLocalAccountUseCase);
    }

    @DisplayName("로컬 가입은 유효한 CSRF 토큰이 있어도 교차 출처 요청이면 거부한다")
    @Test
    void rejectsCrossOriginRegistration() throws Exception {
        mockMvc.perform(post(AuthController.LOCAL_REGISTRATIONS_PATH)
                        .with(csrf())
                        .header(HttpHeaders.ORIGIN, "https://attacker.example")
                        .header("Sec-Fetch-Site", "cross-site")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRegistration()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ORIGIN_DENIED"));

        verifyNoInteractions(registerLocalAccountUseCase);
    }

    @DisplayName("local 등록은 Fetch Metadata가 누락돼도 동일 출처로 추측하지 않는다")
    @Test
    void rejectsRegistrationWithoutFetchMetadata() throws Exception {
        mockMvc.perform(post(AuthController.LOCAL_REGISTRATIONS_PATH)
                        .with(csrf())
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRegistration()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ORIGIN_DENIED"));

        verifyNoInteractions(registerLocalAccountUseCase);
    }

    @DisplayName("자동 요청 방지 실패는 가입과 재설정의 메일 처리를 모두 차단한다")
    @Test
    void rejectsEmailRequestsBeforeDispatch() throws Exception {
        doThrow(new com.personal.baton.application.identity.error.HumanVerificationRejectedException())
                .when(humanVerificationUseCase).verify(any());
        for (String path : java.util.List.of(AuthController.LOCAL_REGISTRATIONS_PATH,
                AuthController.PASSWORD_RESET_REQUESTS_PATH)) {
            mockMvc.perform(sameOrigin(post(path)).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"challenge@example.com\",\"displayName\":\"사용자\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("HUMAN_VERIFICATION_FAILED"));
        }
        verifyNoInteractions(registerLocalAccountUseCase, passwordResetUseCase);
    }

    @DisplayName("검증 공급자 장애 시 메일을 보내지 않고 재시도 가능한 오류를 반환한다")
    @Test
    void stopsEmailRequestsWhenVerificationIsUnavailable() throws Exception {
        doThrow(new com.personal.baton.application.identity.error.HumanVerificationUnavailableException())
                .when(humanVerificationUseCase).verify(any());
        mockMvc.perform(sameOrigin(post(AuthController.LOCAL_REGISTRATIONS_PATH)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"challenge-outage@example.com\",\"displayName\":\"사용자\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("HUMAN_VERIFICATION_UNAVAILABLE"));
        verifyNoInteractions(registerLocalAccountUseCase);
    }

    @DisplayName("이미 검증된 이메일 충돌도 신규 등록과 같은 accepted 응답을 반환한다")
    @Test
    void hidesVerifiedEmailConflict() throws Exception {
        when(registerLocalAccountUseCase.registerLocalAccount(any()))
                .thenThrow(new IdentityConflictException("이미 등록된 계정입니다"));

        mockMvc.perform(sameOrigin(post(AuthController.LOCAL_REGISTRATIONS_PATH))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRegistration()))
                .andExpect(status().isAccepted())
                .andExpect(content().json("{\"verificationRequired\":true}", true));
    }

    @DisplayName("DB 잠금 경쟁은 신원 존재를 노출하지 않는 503과 request-id 로그로 남긴다")
    @Test
    void exposesTemporaryIdentityFailureAtObservableRequestBoundary() throws Exception {
        String failureEmail = "locked-web@example.com";
        var cause = new IdentityOperationUnavailableException(
                "계정 신원을 일시적으로 잠글 수 없습니다",
                new IllegalStateException("Lock wait timeout exceeded; email=" + failureEmail)
        );
        when(registerLocalAccountUseCase.registerLocalAccount(any())).thenThrow(cause);
        Logger logger = (Logger) LoggerFactory.getLogger(RequestIdFilter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            MvcResult result = mockMvc.perform(
                            sameOrigin(post(AuthController.LOCAL_REGISTRATIONS_PATH))
                                    .with(csrf())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(validRegistration().replace(EMAIL, failureEmail))
                    )
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().exists(RequestIdFilter.HEADER_NAME))
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                    .andExpect(content().json("""
                            {
                              "code": "IDENTITY_TEMPORARILY_UNAVAILABLE",
                              "message": "현재 인증 요청을 처리할 수 없습니다. 잠시 후 다시 시도해 주세요"
                            }
                            """, true))
                    .andReturn();

            String requestId = result.getResponse().getHeader(RequestIdFilter.HEADER_NAME);
            assertThat(requestId).isNotBlank();
            assertThat(result.getResponse().getContentAsString())
                    .doesNotContain(failureEmail, "Lock wait timeout", cause.getMessage());
            assertThat(appender.list).singleElement().satisfies(event -> {
                assertThat(event.getMDCPropertyMap())
                        .containsEntry(REQUEST_ID_MDC_KEY, requestId);
                assertThat(event.getFormattedMessage()).contains(
                        "method=POST",
                        "path=" + AuthController.LOCAL_REGISTRATIONS_PATH,
                        "status=503"
                );
            });
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @DisplayName("메일 전달 adapter가 비활성화되면 계정 존재 여부 없이 일반화된 503을 반환한다")
    @Test
    void failsClosedWhenEmailDeliveryIsUnavailable() throws Exception {
        when(registerLocalAccountUseCase.registerLocalAccount(any()))
                .thenThrow(new EmailVerificationDeliveryUnavailableException(
                        "메일 전달이 비활성화됐습니다"
                ));

        mockMvc.perform(sameOrigin(post(AuthController.LOCAL_REGISTRATIONS_PATH))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRegistration().replace(
                                EMAIL,
                                "delivery@example.com"
                        )))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("EMAIL_VERIFICATION_UNAVAILABLE"))
                .andExpect(jsonPath("$.message")
                        .value("현재 이메일 인증을 시작할 수 없습니다"));
    }

    @DisplayName("아웃박스 암호화 키가 없으면 평문으로 저장하지 않고 세부 원인을 숨긴 503을 반환한다")
    @Test
    void failsClosedWhenOutboxProtectionIsUnavailable() throws Exception {
        when(registerLocalAccountUseCase.registerLocalAccount(any()))
                .thenThrow(new EmailVerificationPayloadProtectionException(
                        "암호화 키가 설정되지 않았습니다",
                        true
                ));

        mockMvc.perform(sameOrigin(post(AuthController.LOCAL_REGISTRATIONS_PATH))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRegistration()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("EMAIL_VERIFICATION_UNAVAILABLE"))
                .andExpect(jsonPath("$.message")
                        .value("현재 이메일 인증을 시작할 수 없습니다"));
    }

    @DisplayName("알 수 없거나 만료된 이메일 검증 토큰은 같은 오류로 응답한다")
    @Test
    void generalizesInvalidVerificationToken() throws Exception {
        doThrow(new EmailVerificationException())
                .when(verifyLocalEmailUseCase)
                .verifyLocalEmail(any());

        mockMvc.perform(sameOrigin(post(AuthController.LOCAL_EMAIL_VERIFICATIONS_PATH))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "token": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                                  "password": "correct horse battery staple"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EMAIL_VERIFICATION_INVALID"));
    }

    @DisplayName("검증된 local 계정 로그인은 session ID를 교체하고 canonical account session을 저장한다")
    @Test
    void establishesLocalSessionWithSessionFixationProtection() throws Exception {
        String passwordHash = passwordEncoder.encode(PASSWORD);
        when(loadLocalCredentialUseCase.loadLocalCredential(EMAIL))
                .thenReturn(Optional.of(new LocalCredentialResult(
                        ACCOUNT_ID,
                        passwordHash,
                        true, 0
                )));
        MockHttpSession originalSession = new MockHttpSession();
        String originalSessionId = originalSession.getId();

        MvcResult loginResult = mockMvc.perform(sameOrigin(post(AuthController.LOCAL_SESSION_PATH))
                        .session(originalSession)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("email", EMAIL)
                        .param("password", PASSWORD))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andReturn();

        MockHttpSession authenticatedSession =
                (MockHttpSession) loginResult.getRequest().getSession(false);
        assertThat(authenticatedSession).isNotNull();
        assertThat(authenticatedSession.getId()).isNotEqualTo(originalSessionId);
        SecurityContext context = (SecurityContext) authenticatedSession.getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY
        );
        assertThat(context).isNotNull();
        assertThat(context.getAuthentication().getPrincipal())
                .isInstanceOf(AccountSessionPrincipal.class);
        assertThat(((AccountSessionPrincipal) context.getAuthentication().getPrincipal())
                .accountId()).isEqualTo(ACCOUNT_ID);
        assertThat(context.getAuthentication().getCredentials()).isNull();
        assertThat(context.getAuthentication().getAuthorities())
                .extracting(Object::toString)
                .containsExactly("ROLE_ACCOUNT");

        mockMvc.perform(get(AuthController.SESSION_PATH).session(authenticatedSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.accountId").value(ACCOUNT_ID.toString()));
    }

    @DisplayName("낮은 cost의 legacy bcrypt 로그인은 성공과 같은 흐름에서 현재 encoder hash로 갱신한다")
    @Test
    void upgradesLegacyPasswordHashAfterSuccessfulLogin() throws Exception {
        String legacyHash = "{bcrypt}" + new BCryptPasswordEncoder(4).encode(PASSWORD);
        when(loadLocalCredentialUseCase.loadLocalCredential(EMAIL))
                .thenReturn(Optional.of(new LocalCredentialResult(
                        ACCOUNT_ID,
                        legacyHash,
                        true, 0
                )));

        mockMvc.perform(sameOrigin(post(AuthController.LOCAL_SESSION_PATH))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("email", EMAIL)
                        .param("password", PASSWORD))
                .andExpect(status().isNoContent());

        ArgumentCaptor<UpdateLocalCredentialPasswordCommand> command =
                ArgumentCaptor.forClass(UpdateLocalCredentialPasswordCommand.class);
        verify(updateLocalCredentialPasswordUseCase)
                .updateLocalCredentialPassword(command.capture());
        assertThat(command.getValue().accountId()).isEqualTo(ACCOUNT_ID);
        String encodedPassword = command.getValue().encodedPassword();
        assertThat(encodedPassword).startsWith("{bcrypt}$2");
        assertThat(command.getValue().toString()).doesNotContain(encodedPassword);
    }

    @DisplayName("미검증 계정과 잘못된 비밀번호는 동일한 local 로그인 오류를 반환한다")
    @Test
    void generalizesLocalLoginFailures() throws Exception {
        String passwordHash = passwordEncoder.encode(PASSWORD);
        when(loadLocalCredentialUseCase.loadLocalCredential(EMAIL))
                .thenReturn(Optional.of(new LocalCredentialResult(
                        ACCOUNT_ID,
                        passwordHash,
                        false, 0
                )));

        mockMvc.perform(sameOrigin(post(AuthController.LOCAL_SESSION_PATH))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("email", EMAIL)
                        .param("password", PASSWORD))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.message")
                        .value("이메일 또는 비밀번호가 올바르지 않습니다"));

        when(loadLocalCredentialUseCase.loadLocalCredential(EMAIL))
                .thenReturn(Optional.of(new LocalCredentialResult(
                        ACCOUNT_ID,
                        passwordHash,
                        true, 0
                )));

        mockMvc.perform(sameOrigin(post(AuthController.LOCAL_SESSION_PATH))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("email", EMAIL)
                .param("password", "a completely wrong password"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.message")
                        .value("이메일 또는 비밀번호가 올바르지 않습니다"));
    }

    @DisplayName("인증 정보가 없는 소셜 가입은 OAuth 시작 경로를 노출하지 않는다")
    @Test
    void omitsUnavailableSocialLogin() throws Exception {
        mockMvc.perform(get("/oauth2/authorization/google"))
                .andExpect(status().isForbidden())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().json("""
                        {
                          "code": "REQUEST_FORBIDDEN",
                          "message": "요청을 허용할 수 없습니다"
                        }
                        """, true));
    }

    @Test
    @DisplayName("재설정 API도 CSRF와 동일 출처 검사를 통과해야 한다")
    void passwordResetUsesExistingMutationProtection() throws Exception {
        for (String path : new String[] {AuthController.PASSWORD_RESET_REQUESTS_PATH, AuthController.PASSWORD_RESETS_PATH}) {
            mockMvc.perform(sameOrigin(post(path)).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post(path).with(csrf().asHeader())
                            .header(HttpHeaders.ORIGIN, "https://foreign.example")
                            .header("Sec-Fetch-Site", "cross-site")
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(passwordResetUseCase);
        mockMvc.perform(sameOrigin(post(AuthController.PASSWORD_RESET_REQUESTS_PATH)).with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"reset@example.com\"}"))
                .andExpect(status().isAccepted());
        verify(passwordResetUseCase).requestPasswordReset("reset@example.com");
    }

    @Test
    @DisplayName("기존 계정 세션은 모두 거부하지만 DB 장애만으로 세션을 지우지는 않는다")
    void revokesAllOldSessionsWithoutLoggingOutDuringDatabaseFailure() throws Exception {
        MockHttpSession first = accountSession(0);
        MockHttpSession second = accountSession(0);
        when(validateAccountSessionUseCase.isAccountSessionCurrent(ACCOUNT_ID, 0))
                .thenThrow(new IdentityOperationUnavailableException("DB 조회 실패", new RuntimeException()));
        mockMvc.perform(get(AuthController.SESSION_PATH).session(first))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("IDENTITY_TEMPORARILY_UNAVAILABLE"));
        assertThat(first.isInvalid()).isFalse();

        doReturn(false).when(validateAccountSessionUseCase).isAccountSessionCurrent(ACCOUNT_ID, 0);
        for (MockHttpSession session : new MockHttpSession[] {first, second}) {
            mockMvc.perform(get(AuthController.SESSION_PATH).session(session))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.authenticated").value(false));
            assertThat(session.isInvalid()).isTrue();
        }
    }

    @Test
    @DisplayName("재설정 전에 비밀번호를 읽고 늦게 끝난 로그인도 이전 세션 버전으로 거부한다")
    void rejectsLoginCompletedAfterPasswordReset() throws Exception {
        when(loadLocalCredentialUseCase.loadLocalCredential(EMAIL))
                .thenReturn(Optional.of(new LocalCredentialResult(ACCOUNT_ID, passwordEncoder.encode(PASSWORD), true, 4)));
        when(validateAccountSessionUseCase.isAccountSessionCurrent(ACCOUNT_ID, 4)).thenReturn(false);
        var result = mockMvc.perform(sameOrigin(post(AuthController.LOCAL_SESSION_PATH)).with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED).param("email", EMAIL).param("password", PASSWORD))
                .andExpect(status().isNoContent()).andReturn();
        var session = (MockHttpSession) result.getRequest().getSession(false);
        mockMvc.perform(get(AuthController.SESSION_PATH).session(session))
                .andExpect(jsonPath("$.authenticated").value(false));
        assertThat(session.isInvalid()).isTrue();
    }

    private MockHttpSession accountSession(long version) {
        MockHttpSession session = new MockHttpSession();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new AccountSessionPrincipal(ACCOUNT_ID, version), null, List.of()));
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        return session;
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder sameOrigin(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request
    ) {
        return request
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .header("Sec-Fetch-Site", "same-origin");
    }

    private LocalAccountPrincipal principal() {
        return new LocalAccountPrincipal(
                ACCOUNT_ID,
                EMAIL,
                passwordEncoder.encode(PASSWORD), 0
        );
    }

    private UsernamePasswordAuthenticationToken authenticated(
            LocalAccountPrincipal principal
    ) {
        return UsernamePasswordAuthenticationToken.authenticated(
                principal,
                null,
                principal.getAuthorities()
        );
    }

    private MockHttpSession authenticatedSession(LocalAccountPrincipal principal) {
        MockHttpSession session = new MockHttpSession();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authenticated(principal));
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                context
        );
        return session;
    }

    private String validRegistration() {
        return """
                {
                  "email": "member@example.com",
                  "displayName": "박민서"
                }
                """;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class PasswordEncoderTestConfig {

        @Bean
        PasswordEncoder passwordEncoder() {
            return PasswordEncoderFactories.createDelegatingPasswordEncoder();
        }
    }
}
