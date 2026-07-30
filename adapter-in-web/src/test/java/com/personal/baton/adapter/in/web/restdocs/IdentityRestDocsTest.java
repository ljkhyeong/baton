package com.personal.baton.adapter.in.web.restdocs;

import com.epages.restdocs.apispec.ConstrainedFields;
import com.epages.restdocs.apispec.EnumFields;
import com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper;
import com.personal.baton.adapter.in.web.RequestIdFilter;
import com.personal.baton.adapter.in.web.config.SecurityConfig;
import com.personal.baton.adapter.in.web.config.WebFilterConfig;
import com.personal.baton.adapter.in.web.identity.BatonAccountPrincipal;
import com.personal.baton.adapter.in.web.identity.IdentityController;
import com.personal.baton.adapter.in.web.identity.IdentityRequests;
import com.personal.baton.adapter.in.web.identity.IdentitySessionController;
import com.personal.baton.application.identity.error.IdentityNotFoundException;
import com.personal.baton.application.identity.error.IdentityOperationException;
import com.personal.baton.application.identity.port.in.IdentityInvitationAcceptanceUseCase;
import com.personal.baton.application.identity.port.in.IdentityInvitationAcceptanceUseCase.AcceptedInvitation;
import com.personal.baton.application.identity.port.in.MemberIdentityUseCase;
import com.personal.baton.application.identity.port.in.MemberIdentityUseCase.AuthenticatedAccount;
import com.personal.baton.application.identity.port.in.MemberInvitationUseCase;
import com.personal.baton.application.identity.port.in.OwnerBootstrapInvitationUseCase;
import com.personal.baton.application.identity.port.in.OwnerBootstrapInvitationUseCase.IssueOwnerBootstrapInvitationCommand;
import com.personal.baton.application.identity.port.in.OwnerBootstrapInvitationUseCase.IssuedOwnerBootstrapInvitation;
import com.personal.baton.domain.identity.MemberIdentityRole;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.restdocs.RestDocumentationContextProvider;
import org.springframework.restdocs.RestDocumentationExtension;
import org.springframework.restdocs.headers.HeaderDescriptor;
import org.springframework.restdocs.headers.ResponseHeadersSnippet;
import org.springframework.restdocs.mockmvc.RestDocumentationResultHandler;
import org.springframework.restdocs.payload.FieldDescriptor;
import org.springframework.restdocs.payload.JsonFieldType;
import org.springframework.restdocs.snippet.Snippet;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import static org.mockito.Mockito.when;
import static org.springframework.restdocs.headers.HeaderDocumentation.headerWithName;
import static org.springframework.restdocs.headers.HeaderDocumentation.requestHeaders;
import static org.springframework.restdocs.headers.HeaderDocumentation.responseHeaders;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.documentationConfiguration;
import static org.springframework.restdocs.operation.preprocess.Preprocessors.prettyPrint;
import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;
import static org.springframework.restdocs.payload.PayloadDocumentation.requestFields;
import static org.springframework.restdocs.payload.PayloadDocumentation.responseFields;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@Tag("restdocs")
@ExtendWith(RestDocumentationExtension.class)
@WebMvcTest(controllers = {
        IdentityController.class,
        IdentitySessionController.class
})
@Import({SecurityConfig.class, WebFilterConfig.class})
class IdentityRestDocsTest {

    private static final UUID REQUEST_ID =
            UUID.fromString("11111111-2222-4333-8444-555555555555");
    private static final UUID ACCOUNT_ID =
            UUID.fromString("22222222-2222-4333-8444-555555555555");
    private static final UUID TEAM_ID =
            UUID.fromString("33333333-2222-4333-8444-555555555555");
    private static final UUID MEMBER_ID =
            UUID.fromString("44444444-2222-4333-8444-555555555555");
    private static final UUID INVITATION_ID =
            UUID.fromString("55555555-2222-4333-8444-555555555555");
    private static final String IDEMPOTENCY_KEY =
            "66666666-2222-4333-8444-555555555555";
    private static final String BOOTSTRAP_KEY = "operator-bootstrap-key";
    private static final String INVITATION_TOKEN = "A".repeat(43);
    private static final OperationDocumentation GET_IDENTITY_SESSION =
            new OperationDocumentation(
                    "로그인 세션 조회",
                    "현재 BATON 로그인 여부와 인증 세션에 결속된 CSRF 토큰을 조회한다."
            );
    private static final OperationDocumentation GET_ME =
            new OperationDocumentation(
                    "내 계정 조회",
                    "로그인 세션의 공급자 중립 BATON 내부 계정 식별자를 조회한다."
            );
    private static final OperationDocumentation LOGOUT_SESSION =
            new OperationDocumentation(
                    "로그인 세션 종료",
                    "CSRF 토큰을 검증한 뒤 현재 BATON 로그인 세션을 무효화한다."
            );
    private static final OperationDocumentation ISSUE_BOOTSTRAP_INVITATION =
            new OperationDocumentation(
                    "기존 팀 OWNER bootstrap 초대 발급",
                    "운영자 bootstrap 키를 검증하고 기존 팀 구성원에 결속된 일회성 OWNER 초대를 발급한다."
            );
    private static final OperationDocumentation ACCEPT_INVITATION =
            new OperationDocumentation(
                    "구성원 초대 수락",
                    "로그인한 BATON 계정이 JSON 본문의 일회성 초대를 소비하고 팀 구성원과 결속된다."
            );

    @Autowired
    private WebApplicationContext applicationContext;

    @MockitoBean
    private OwnerBootstrapInvitationUseCase invitationUseCase;

    @MockitoBean
    private IdentityInvitationAcceptanceUseCase invitationAcceptanceUseCase;

    @MockitoBean
    private MemberIdentityUseCase memberIdentityUseCase;

    @MockitoBean
    private MemberInvitationUseCase memberInvitationUseCase;

    @MockitoBean
    private Clock clock;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp(RestDocumentationContextProvider restDocumentation) {
        when(clock.instant()).thenReturn(Instant.parse("2026-07-30T12:00:00Z"));
        mockMvc = webAppContextSetup(applicationContext)
                .addFilters(new RequestIdFilter(() -> REQUEST_ID))
                .apply(springSecurity())
                .apply(documentationConfiguration(restDocumentation)
                        .operationPreprocessors()
                        .withRequestDefaults(prettyPrint())
                        .withResponseDefaults(prettyPrint()))
                .alwaysExpect(header().string(
                        RequestIdFilter.HEADER_NAME,
                        REQUEST_ID.toString()
                ))
                .build();
    }

    @DisplayName("세션 조회 API는 로그인 상태와 CSRF 토큰을 반환한다")
    @Test
    void documentsIdentitySession() throws Exception {
        mockMvc.perform(get("/api/v1/auth/session")
                        .with(authentication(accountAuthentication())))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.accountId").value(ACCOUNT_ID.toString()))
                .andExpect(jsonPath("$.csrfHeaderName").value("X-CSRF-TOKEN"))
                .andExpect(jsonPath("$.csrfToken").isNotEmpty())
                .andExpect(jsonPath("$.oidcEnabled").value(false))
                .andDo(document(
                        "getIdentitySession",
                        GET_IDENTITY_SESSION,
                        noStoreResponseHeader(),
                        responseFields(
                                fieldWithPath("authenticated")
                                        .description("BATON 계정 로그인 여부"),
                                fieldWithPath("accountId")
                                        .type(JsonFieldType.STRING)
                                        .optional()
                                        .description("로그인한 BATON 내부 계정 UUID"),
                                fieldWithPath("csrfHeaderName")
                                        .type(JsonFieldType.STRING)
                                        .optional()
                                        .description("변경 요청에서 사용할 CSRF 헤더 이름"),
                                fieldWithPath("csrfToken")
                                        .type(JsonFieldType.STRING)
                                        .optional()
                                        .description("현재 인증 세션에 결속된 CSRF 토큰"),
                                fieldWithPath("oidcEnabled")
                                        .description("Google OIDC 로그인을 사용할 수 있는지 여부")
                        )));
    }

    @DisplayName("내 계정 API는 공급자 중립 내부 계정 식별자를 반환한다")
    @Test
    void documentsMe() throws Exception {
        mockMvc.perform(get("/api/v1/me")
                        .with(authentication(accountAuthentication())))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.accountId").value(ACCOUNT_ID.toString()))
                .andDo(document(
                        "getMe",
                        GET_ME,
                        noStoreResponseHeader(),
                        responseFields(fieldWithPath("accountId")
                                .description("공급자 중립 BATON 내부 계정 UUID"))
                ));
    }

    @DisplayName("내 계정 API는 로그인하지 않으면 JSON 401을 반환한다")
    @Test
    void documentsMeAuthenticationRequired() throws Exception {
        mockMvc.perform(get("/api/v1/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                .andDo(document(
                        "getMeAuthenticationRequired",
                        GET_ME,
                        noStoreResponseHeader(),
                        responseFields(errorResponseFields())
                ));
    }

    @DisplayName("로그아웃 API는 현재 세션을 무효화하고 204를 반환한다")
    @Test
    void documentsLogout() throws Exception {
        mockMvc.perform(post("/api/v1/session/logout")
                        .with(authentication(accountAuthentication()))
                        .with(csrf().asHeader()))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andDo(document(
                        "logoutSession",
                        LOGOUT_SESSION,
                        csrfHeader(),
                        noStoreResponseHeader()
                ));
    }

    @DisplayName("로그아웃 API는 CSRF 토큰이 없으면 JSON 403을 반환한다")
    @Test
    void documentsLogoutCsrfInvalid() throws Exception {
        mockMvc.perform(post("/api/v1/session/logout")
                        .with(authentication(accountAuthentication())))
                .andExpect(status().isForbidden())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"))
                .andDo(document(
                        "logoutSessionCsrfInvalid",
                        LOGOUT_SESSION,
                        optionalCsrfHeader(),
                        noStoreResponseHeader(),
                        responseFields(errorResponseFields())
                ));
    }

    @DisplayName("bootstrap 초대 발급 API는 최초 요청에 원문 토큰을 201로 반환한다")
    @Test
    void documentsBootstrapInvitationIssue() throws Exception {
        when(invitationUseCase.issue(
                BOOTSTRAP_KEY,
                IDEMPOTENCY_KEY,
                new IssueOwnerBootstrapInvitationCommand(TEAM_ID, MEMBER_ID)
        )).thenReturn(issuedInvitation(false));

        mockMvc.perform(post("/api/v1/identity/bootstrap-invitations")
                        .header(
                                "X-Baton-Identity-Bootstrap-Key",
                                BOOTSTRAP_KEY
                        )
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(issueRequest()))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.token").value(INVITATION_TOKEN))
                .andDo(document(
                        "issueBootstrapInvitation",
                        ISSUE_BOOTSTRAP_INVITATION,
                        bootstrapHeaders(),
                        requestFields(
                                requestField(
                                        IdentityRequests.IssueBootstrapInvitationRequest.class,
                                        "teamId",
                                        "초대를 발급할 기존 팀 UUID"
                                ),
                                requestField(
                                        IdentityRequests.IssueBootstrapInvitationRequest.class,
                                        "memberId",
                                        "OWNER 계정과 결속할 기존 활성 구성원 UUID"
                                )
                        ),
                        noStoreResponseHeader(),
                        responseFields(bootstrapInvitationResponseFields())
                ));
    }

    @DisplayName("bootstrap 초대 발급 API는 동일한 멱등 요청 재생에 같은 토큰을 200으로 반환한다")
    @Test
    void documentsBootstrapInvitationReplay() throws Exception {
        when(invitationUseCase.issue(
                BOOTSTRAP_KEY,
                IDEMPOTENCY_KEY,
                new IssueOwnerBootstrapInvitationCommand(TEAM_ID, MEMBER_ID)
        )).thenReturn(issuedInvitation(true));

        mockMvc.perform(post("/api/v1/identity/bootstrap-invitations")
                        .header(
                                "X-Baton-Identity-Bootstrap-Key",
                                BOOTSTRAP_KEY
                        )
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(issueRequest()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.invitationId")
                        .value(INVITATION_ID.toString()))
                .andDo(document(
                        "issueBootstrapInvitationReplay",
                        ISSUE_BOOTSTRAP_INVITATION,
                        bootstrapHeaders(),
                        requestFields(
                                requestField(
                                        IdentityRequests.IssueBootstrapInvitationRequest.class,
                                        "teamId",
                                        "초대를 발급할 기존 팀 UUID"
                                ),
                                requestField(
                                        IdentityRequests.IssueBootstrapInvitationRequest.class,
                                        "memberId",
                                        "OWNER 계정과 결속할 기존 활성 구성원 UUID"
                                )
                        ),
                        noStoreResponseHeader(),
                        responseFields(bootstrapInvitationResponseFields())
                ));
    }

    @DisplayName("bootstrap 초대 발급 API는 운영자 키가 다르면 JSON 403을 반환한다")
    @Test
    void documentsBootstrapInvitationForbidden() throws Exception {
        when(invitationUseCase.issue(
                "wrong-key",
                IDEMPOTENCY_KEY,
                new IssueOwnerBootstrapInvitationCommand(TEAM_ID, MEMBER_ID)
        )).thenThrow(new IdentityOperationException(
                "BOOTSTRAP_INVITATION_FORBIDDEN",
                "bootstrap 초대를 발급할 권한이 없습니다"
        ));

        mockMvc.perform(post("/api/v1/identity/bootstrap-invitations")
                        .header(
                                "X-Baton-Identity-Bootstrap-Key",
                                "wrong-key"
                        )
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(issueRequest()))
                .andExpect(status().isForbidden())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code")
                        .value("BOOTSTRAP_INVITATION_FORBIDDEN"))
                .andDo(document(
                        "issueBootstrapInvitationForbidden",
                        ISSUE_BOOTSTRAP_INVITATION,
                        bootstrapHeaders(),
                        noStoreResponseHeader(),
                        responseFields(errorResponseFields())
                ));
    }

    @DisplayName("bootstrap 초대 발급 API는 필수 대상이 없으면 JSON 400을 반환한다")
    @Test
    void documentsBootstrapInvitationInvalidInput() throws Exception {
        mockMvc.perform(post("/api/v1/identity/bootstrap-invitations")
                        .header(
                                "X-Baton-Identity-Bootstrap-Key",
                                BOOTSTRAP_KEY
                        )
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "teamId": null,
                                  "memberId": "%s"
                                }
                                """.formatted(MEMBER_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andDo(document(
                        "issueBootstrapInvitationInvalidInput",
                        ISSUE_BOOTSTRAP_INVITATION,
                        bootstrapHeaders(),
                        noStoreResponseHeader(),
                        responseFields(errorResponseFields())
                ));
    }

    @DisplayName("bootstrap 초대 발급 API는 대상 구성원이 없으면 JSON 404를 반환한다")
    @Test
    void documentsBootstrapInvitationNotFound() throws Exception {
        documentBootstrapInvitationIssueError(
                "issueBootstrapInvitationNotFound",
                new IdentityNotFoundException(
                        "MEMBER_NOT_FOUND",
                        "구성원을 찾을 수 없습니다"
                ),
                HttpStatus.NOT_FOUND,
                "MEMBER_NOT_FOUND"
        );
    }

    @DisplayName("bootstrap 초대 발급 API는 대상이나 OWNER가 이미 결속됐으면 JSON 409를 반환한다")
    @Test
    void documentsBootstrapInvitationConflict() throws Exception {
        documentBootstrapInvitationIssueError(
                "issueBootstrapInvitationConflict",
                new IdentityOperationException(
                        "BOOTSTRAP_TARGET_UNAVAILABLE",
                        "bootstrap 초대 대상을 사용할 수 없습니다"
                ),
                HttpStatus.CONFLICT,
                "BOOTSTRAP_TARGET_UNAVAILABLE"
        );
    }

    @DisplayName("bootstrap 초대 발급 API는 안전한 운영 설정이 없으면 JSON 503을 반환한다")
    @Test
    void documentsBootstrapInvitationUnavailable() throws Exception {
        documentBootstrapInvitationIssueError(
                "issueBootstrapInvitationUnavailable",
                new IdentityOperationException(
                        "BOOTSTRAP_CONFIGURATION_INVALID",
                        "bootstrap 초대 발급 설정이 안전하지 않습니다"
                ),
                HttpStatus.SERVICE_UNAVAILABLE,
                "BOOTSTRAP_CONFIGURATION_INVALID"
        );
    }

    @DisplayName("초대 수락 API는 일회성 토큰을 소비하고 OWNER 결속을 반환한다")
    @Test
    void documentsInvitationAcceptance() throws Exception {
        when(invitationAcceptanceUseCase.accept(
                INVITATION_TOKEN,
                new AuthenticatedAccount(ACCOUNT_ID)
        )).thenReturn(new AcceptedInvitation(
                INVITATION_ID,
                ACCOUNT_ID,
                TEAM_ID,
                MEMBER_ID,
                Instant.parse("2026-07-30T12:00:00Z"),
                MemberIdentityRole.OWNER
        ));

        mockMvc.perform(post("/api/v1/identity/invitations/accept")
                        .with(authentication(accountAuthentication()))
                        .with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + INVITATION_TOKEN + "\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.accountId").value(ACCOUNT_ID.toString()))
                .andExpect(jsonPath("$.role").value("OWNER"))
                .andDo(document(
                        "acceptInvitation",
                        ACCEPT_INVITATION,
                        csrfHeader(),
                        requestFields(requestField(
                                IdentityRequests.AcceptInvitationRequest.class,
                                "token",
                                "URL이나 header가 아닌 JSON 본문으로만 전달하는 일회성 초대 토큰"
                        )),
                        noStoreResponseHeader(),
                        responseFields(
                                fieldWithPath("invitationId")
                                        .description("소비한 초대 UUID"),
                                fieldWithPath("accountId")
                                        .description("로그인한 BATON 내부 계정 UUID"),
                                fieldWithPath("teamId")
                                        .description("결속한 팀 UUID"),
                                fieldWithPath("memberId")
                                        .description("결속한 구성원 UUID"),
                                fieldWithPath("boundAt")
                                        .description("구성원 신원을 결속한 UTC 시각"),
                                new EnumFields(MemberIdentityRole.class)
                                        .withPath("role")
                                        .description("팀 신원 역할")
                        )));
    }

    @DisplayName("초대 수락 API는 CSRF 토큰이 없으면 JSON 403을 반환한다")
    @Test
    void documentsInvitationAcceptanceCsrfInvalid() throws Exception {
        mockMvc.perform(post("/api/v1/identity/invitations/accept")
                        .with(authentication(accountAuthentication()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + INVITATION_TOKEN + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"))
                .andDo(document(
                        "acceptInvitationCsrfInvalid",
                        ACCEPT_INVITATION,
                        optionalCsrfHeader(),
                        noStoreResponseHeader(),
                        responseFields(errorResponseFields())
                ));
    }

    @DisplayName("초대 수락 API는 로그인하지 않으면 JSON 401을 반환한다")
    @Test
    void documentsInvitationAcceptanceAuthenticationRequired() throws Exception {
        mockMvc.perform(post("/api/v1/identity/invitations/accept")
                        .with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(acceptRequest()))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                .andDo(document(
                        "acceptInvitationAuthenticationRequired",
                        ACCEPT_INVITATION,
                        csrfHeader(),
                        noStoreResponseHeader(),
                        responseFields(errorResponseFields())
                ));
    }

    @DisplayName("초대 수락 API는 빈 토큰이면 JSON 400을 반환한다")
    @Test
    void documentsInvitationAcceptanceInvalidInput() throws Exception {
        mockMvc.perform(post("/api/v1/identity/invitations/accept")
                        .with(authentication(accountAuthentication()))
                        .with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andDo(document(
                        "acceptInvitationInvalidInput",
                        ACCEPT_INVITATION,
                        csrfHeader(),
                        noStoreResponseHeader(),
                        responseFields(errorResponseFields())
                ));
    }

    @DisplayName("초대 수락 API는 토큰을 찾지 못하면 JSON 404를 반환한다")
    @Test
    void documentsInvitationAcceptanceNotFound() throws Exception {
        documentInvitationAcceptanceError(
                "acceptInvitationNotFound",
                new IdentityOperationException(
                        "BOOTSTRAP_INVITATION_NOT_FOUND",
                        "bootstrap 초대를 찾을 수 없습니다"
                ),
                HttpStatus.NOT_FOUND,
                "BOOTSTRAP_INVITATION_NOT_FOUND"
        );
    }

    @DisplayName("초대 수락 API는 다른 계정이 토큰을 사용했으면 JSON 409를 반환한다")
    @Test
    void documentsInvitationAcceptanceConflict() throws Exception {
        documentInvitationAcceptanceError(
                "acceptInvitationConflict",
                new IdentityOperationException(
                        "BOOTSTRAP_INVITATION_USED",
                        "bootstrap 초대가 이미 사용되었습니다"
                ),
                HttpStatus.CONFLICT,
                "BOOTSTRAP_INVITATION_USED"
        );
    }

    @DisplayName("초대 수락 API는 토큰이 만료됐으면 JSON 410을 반환한다")
    @Test
    void documentsInvitationAcceptanceExpired() throws Exception {
        documentInvitationAcceptanceError(
                "acceptInvitationExpired",
                new IdentityOperationException(
                        "BOOTSTRAP_INVITATION_EXPIRED",
                        "bootstrap 초대가 만료되었습니다"
                ),
                HttpStatus.GONE,
                "BOOTSTRAP_INVITATION_EXPIRED"
        );
    }

    private org.springframework.security.core.Authentication accountAuthentication() {
        return org.springframework.security.authentication.UsernamePasswordAuthenticationToken
                .authenticated(
                        new BatonAccountPrincipal(ACCOUNT_ID),
                        null,
                        List.of()
                );
    }

    private IssuedOwnerBootstrapInvitation issuedInvitation(boolean replayed) {
        return new IssuedOwnerBootstrapInvitation(
                INVITATION_ID,
                TEAM_ID,
                MEMBER_ID,
                INVITATION_TOKEN,
                Instant.parse("2026-07-30T12:00:00Z"),
                Instant.parse("2026-07-30T13:00:00Z"),
                replayed
        );
    }

    private String issueRequest() {
        return """
                {
                  "teamId": "%s",
                  "memberId": "%s"
                }
                """.formatted(TEAM_ID, MEMBER_ID);
    }

    private String acceptRequest() {
        return "{\"token\":\"" + INVITATION_TOKEN + "\"}";
    }

    private void documentBootstrapInvitationIssueError(
            String resourceIdentifier,
            RuntimeException exception,
            HttpStatus expectedStatus,
            String expectedCode
    ) throws Exception {
        when(invitationUseCase.issue(
                BOOTSTRAP_KEY,
                IDEMPOTENCY_KEY,
                new IssueOwnerBootstrapInvitationCommand(TEAM_ID, MEMBER_ID)
        )).thenThrow(exception);

        mockMvc.perform(post("/api/v1/identity/bootstrap-invitations")
                        .header(
                                "X-Baton-Identity-Bootstrap-Key",
                                BOOTSTRAP_KEY
                        )
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(issueRequest()))
                .andExpect(status().is(expectedStatus.value()))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value(expectedCode))
                .andDo(document(
                        resourceIdentifier,
                        ISSUE_BOOTSTRAP_INVITATION,
                        bootstrapHeaders(),
                        noStoreResponseHeader(),
                        responseFields(errorResponseFields())
                ));
    }

    private void documentInvitationAcceptanceError(
            String resourceIdentifier,
            RuntimeException exception,
            HttpStatus expectedStatus,
            String expectedCode
    ) throws Exception {
        when(invitationAcceptanceUseCase.accept(
                INVITATION_TOKEN,
                new AuthenticatedAccount(ACCOUNT_ID)
        )).thenThrow(exception);

        mockMvc.perform(post("/api/v1/identity/invitations/accept")
                        .with(authentication(accountAuthentication()))
                        .with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(acceptRequest()))
                .andExpect(status().is(expectedStatus.value()))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value(expectedCode))
                .andDo(document(
                        resourceIdentifier,
                        ACCEPT_INVITATION,
                        csrfHeader(),
                        noStoreResponseHeader(),
                        responseFields(errorResponseFields())
                ));
    }

    private FieldDescriptor requestField(
            Class<?> requestType,
            String path,
            String description
    ) {
        return new ConstrainedFields(requestType)
                .withPath(path)
                .description(description);
    }

    private FieldDescriptor[] bootstrapInvitationResponseFields() {
        return new FieldDescriptor[]{
                fieldWithPath("invitationId").description("발급한 초대 UUID"),
                fieldWithPath("teamId").description("초대 대상 팀 UUID"),
                fieldWithPath("memberId").description("초대 대상 구성원 UUID"),
                fieldWithPath("token")
                        .description("최초 성공과 동일 멱등 재생에서만 반환하는 원문 초대 토큰"),
                fieldWithPath("issuedAt").description("초대 발급 UTC 시각"),
                fieldWithPath("expiresAt").description("초대 만료 UTC 시각")
        };
    }

    private FieldDescriptor[] errorResponseFields() {
        return new FieldDescriptor[]{
                fieldWithPath("code").description("안정적인 오류 코드"),
                fieldWithPath("message").description("사용자에게 표시할 오류 설명")
        };
    }

    private Snippet bootstrapHeaders() {
        return requestHeaders(
                headerWithName("X-Baton-Identity-Bootstrap-Key")
                        .description("외부 edge에서 차단하고 application이 검증하는 운영자 bootstrap 키"),
                headerWithName("Idempotency-Key")
                        .description("동일 발급 의도를 재시도하는 canonical UUID")
        );
    }

    private Snippet csrfHeader() {
        return requestHeaders(headerWithName("X-CSRF-TOKEN")
                .description("현재 인증 세션에 결속된 CSRF 토큰"));
    }

    private Snippet optionalCsrfHeader() {
        return requestHeaders(headerWithName("X-CSRF-TOKEN")
                .optional()
                .description("누락되어 요청이 거부된 CSRF 토큰"));
    }

    private Snippet noStoreResponseHeader() {
        return responseHeadersWithRequestId(
                headerWithName(HttpHeaders.CACHE_CONTROL)
                        .description("신원·세션 응답을 저장하지 않도록 하는 no-store 지시자")
        );
    }

    private Snippet responseHeadersWithRequestId(
            HeaderDescriptor... descriptors
    ) {
        List<HeaderDescriptor> completeDescriptors = new ArrayList<>();
        completeDescriptors.add(headerWithName(RequestIdFilter.HEADER_NAME)
                .description("서버가 생성한 불투명 요청 진단 식별자"));
        completeDescriptors.addAll(Arrays.asList(descriptors));
        return responseHeaders(
                completeDescriptors.toArray(HeaderDescriptor[]::new)
        );
    }

    private RestDocumentationResultHandler document(
            String resourceIdentifier,
            OperationDocumentation operation,
            Snippet... snippets
    ) {
        List<Snippet> completeSnippets =
                new ArrayList<>(Arrays.asList(snippets));
        if (completeSnippets.stream()
                .noneMatch(ResponseHeadersSnippet.class::isInstance)) {
            completeSnippets.add(responseHeadersWithRequestId());
        }
        return MockMvcRestDocumentationWrapper.document(
                resourceIdentifier,
                operation.description(),
                operation.summary(),
                completeSnippets.toArray(Snippet[]::new)
        );
    }

    private record OperationDocumentation(
            String summary,
            String description
    ) {
    }
}
