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
import com.personal.baton.application.identity.error.IdentityNotFoundException;
import com.personal.baton.application.identity.error.IdentityOperationException;
import com.personal.baton.application.identity.port.in.IdentityInvitationAcceptanceUseCase;
import com.personal.baton.application.identity.port.in.IdentityInvitationAcceptanceUseCase.PreviewedInvitation;
import com.personal.baton.application.identity.port.in.MemberIdentityUseCase;
import com.personal.baton.application.identity.port.in.MemberIdentityUseCase.AuthenticatedAccount;
import com.personal.baton.application.identity.port.in.MemberIdentityUseCase.MemberIdentityResult;
import com.personal.baton.application.identity.port.in.MemberInvitationUseCase;
import com.personal.baton.application.identity.port.in.MemberInvitationUseCase.IssueMemberInvitationCommand;
import com.personal.baton.application.identity.port.in.MemberInvitationUseCase.IssuedMemberInvitation;
import com.personal.baton.application.identity.port.in.MemberInvitationUseCase.OpenMemberInvitation;
import com.personal.baton.application.identity.port.in.MemberInvitationUseCase.RevokedMemberInvitation;
import com.personal.baton.application.identity.port.in.OwnerBootstrapInvitationUseCase;
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

import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.restdocs.headers.HeaderDocumentation.headerWithName;
import static org.springframework.restdocs.headers.HeaderDocumentation.requestHeaders;
import static org.springframework.restdocs.headers.HeaderDocumentation.responseHeaders;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.documentationConfiguration;
import static org.springframework.restdocs.operation.preprocess.Preprocessors.prettyPrint;
import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;
import static org.springframework.restdocs.payload.PayloadDocumentation.requestFields;
import static org.springframework.restdocs.payload.PayloadDocumentation.responseFields;
import static org.springframework.restdocs.request.RequestDocumentation.parameterWithName;
import static org.springframework.restdocs.request.RequestDocumentation.pathParameters;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.get;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@Tag("restdocs")
@ExtendWith(RestDocumentationExtension.class)
@WebMvcTest(controllers = IdentityController.class)
@Import({SecurityConfig.class, WebFilterConfig.class})
class MemberInvitationRestDocsTest {

    private static final UUID REQUEST_ID =
            UUID.fromString("11111111-3333-4333-8444-555555555555");
    private static final UUID ACCOUNT_ID =
            UUID.fromString("22222222-3333-4333-8444-555555555555");
    private static final UUID TEAM_ID =
            UUID.fromString("33333333-3333-4333-8444-555555555555");
    private static final UUID MEMBER_ID =
            UUID.fromString("44444444-3333-4333-8444-555555555555");
    private static final UUID INVITATION_ID =
            UUID.fromString("55555555-3333-4333-8444-555555555555");
    private static final String IDEMPOTENCY_KEY =
            "66666666-3333-4333-8444-555555555555";
    private static final String INVITATION_TOKEN = "mi1_" + "A".repeat(43);
    private static final Instant ISSUED_AT =
            Instant.parse("2026-07-30T12:00:00Z");
    private static final Instant EXPIRES_AT =
            Instant.parse("2026-07-31T12:00:00Z");

    private static final OperationDocumentation GET_TEAM_MEMBERSHIP =
            new OperationDocumentation(
                    "팀 로그인 소속 조회",
                    "현재 로그인 계정과 결속된 활성 팀 구성원 및 역할을 조회한다."
            );
    private static final OperationDocumentation PREVIEW_INVITATION =
            new OperationDocumentation(
                    "구성원 초대 미리보기",
                    "초대를 소비하기 전에 대상 팀·구성원·역할과 만료 시각을 확인한다."
            );
    private static final OperationDocumentation ISSUE_MEMBER_INVITATION =
            new OperationDocumentation(
                    "일반 구성원 초대 발급",
                    "현재 활성 OWNER가 기존 활성 구성원에 결속할 일회성 초대를 발급한다."
            );
    private static final OperationDocumentation LIST_MEMBER_INVITATIONS =
            new OperationDocumentation(
                    "열린 구성원 초대 목록",
                    "현재 활성 OWNER가 팀의 만료되지 않은 미소비 초대를 조회한다."
            );
    private static final OperationDocumentation REVOKE_MEMBER_INVITATION =
            new OperationDocumentation(
                    "구성원 초대 폐기",
                    "현재 활성 OWNER가 아직 소비되지 않은 구성원 초대를 폐기한다."
            );

    @Autowired
    private WebApplicationContext applicationContext;

    @MockitoBean
    private OwnerBootstrapInvitationUseCase bootstrapInvitationUseCase;

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
        when(clock.instant()).thenReturn(ISSUED_AT);
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

    @DisplayName("팀 소속 조회 API는 로그인 계정의 활성 구성원 역할을 반환한다")
    @Test
    void documentsTeamMembership() throws Exception {
        when(memberIdentityUseCase.findActiveMember(
                TEAM_ID,
                account()
        )).thenReturn(java.util.Optional.of(new MemberIdentityResult(
                ACCOUNT_ID,
                TEAM_ID,
                MEMBER_ID,
                ISSUED_AT,
                MemberIdentityRole.OWNER
        )));

        mockMvc.perform(get("/api/v1/teams/{teamId}/membership", TEAM_ID)
                        .with(authentication(accountAuthentication())))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.role").value("OWNER"))
                .andDo(document(
                        "getTeamMembership",
                        GET_TEAM_MEMBERSHIP,
                        teamPath(),
                        noStoreResponseHeader(),
                        responseFields(membershipFields())
                ));
    }

    @DisplayName("팀 소속 조회 API는 인증 누락과 미결속을 안정적인 오류로 반환한다")
    @Test
    void documentsTeamMembershipErrors() throws Exception {
        mockMvc.perform(get("/api/v1/teams/{teamId}/membership", TEAM_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andDo(document(
                        "getTeamMembershipAuthenticationRequired",
                        GET_TEAM_MEMBERSHIP,
                        teamPath(),
                        noStoreResponseHeader(),
                        responseFields(errorFields())
                ));

        when(memberIdentityUseCase.findActiveMember(
                TEAM_ID,
                account()
        )).thenReturn(java.util.Optional.empty());
        mockMvc.perform(get("/api/v1/teams/{teamId}/membership", TEAM_ID)
                        .with(authentication(accountAuthentication())))
                .andExpect(status().isNotFound())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value("MEMBERSHIP_NOT_FOUND"))
                .andDo(document(
                        "getTeamMembershipNotFound",
                        GET_TEAM_MEMBERSHIP,
                        teamPath(),
                        noStoreResponseHeader(),
                        responseFields(errorFields())
                ));
    }

    @DisplayName("초대 미리보기 API는 소비 전에 대상과 역할을 반환한다")
    @Test
    void documentsInvitationPreview() throws Exception {
        when(invitationAcceptanceUseCase.preview(
                INVITATION_TOKEN,
                account()
        )).thenReturn(new PreviewedInvitation(
                TEAM_ID,
                "BATON 팀",
                MEMBER_ID,
                "김바톤",
                MemberIdentityRole.MEMBER,
                EXPIRES_AT,
                false
        ));

        mockMvc.perform(post("/api/v1/identity/invitations/preview")
                        .with(authentication(accountAuthentication()))
                        .with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tokenRequest()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.memberName").value("김바톤"))
                .andExpect(jsonPath("$.role").value("MEMBER"))
                .andDo(document(
                        "previewInvitation",
                        PREVIEW_INVITATION,
                        csrfHeader(),
                        requestFields(tokenRequestField()),
                        noStoreResponseHeader(),
                        responseFields(previewFields())
                ));
    }

    @DisplayName("초대 미리보기 API는 입력·인증·상태 오류를 no-store로 반환한다")
    @Test
    void documentsInvitationPreviewErrors() throws Exception {
        mockMvc.perform(post("/api/v1/identity/invitations/preview")
                        .with(authentication(accountAuthentication()))
                        .with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andDo(document(
                        "previewInvitationInvalidInput",
                        PREVIEW_INVITATION,
                        csrfHeader(),
                        noStoreResponseHeader(),
                        responseFields(errorFields())
                ));

        mockMvc.perform(post("/api/v1/identity/invitations/preview")
                        .with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tokenRequest()))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andDo(document(
                        "previewInvitationAuthenticationRequired",
                        PREVIEW_INVITATION,
                        csrfHeader(),
                        noStoreResponseHeader(),
                        responseFields(errorFields())
                ));

        mockMvc.perform(post("/api/v1/identity/invitations/preview")
                        .with(authentication(accountAuthentication()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tokenRequest()))
                .andExpect(status().isForbidden())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andDo(document(
                        "previewInvitationCsrfInvalid",
                        PREVIEW_INVITATION,
                        optionalCsrfHeader(),
                        noStoreResponseHeader(),
                        responseFields(errorFields())
                ));

        documentPreviewApplicationError(
                "previewInvitationNotFound",
                new IdentityOperationException(
                        "MEMBER_INVITATION_NOT_FOUND",
                        "구성원 초대를 찾을 수 없습니다"
                ),
                HttpStatus.NOT_FOUND
        );
        documentPreviewApplicationError(
                "previewInvitationConflict",
                new IdentityOperationException(
                        "MEMBER_INVITATION_TARGET_UNAVAILABLE",
                        "구성원 초대 대상을 사용할 수 없습니다"
                ),
                HttpStatus.CONFLICT
        );
        documentPreviewApplicationError(
                "previewInvitationExpired",
                new IdentityOperationException(
                        "MEMBER_INVITATION_EXPIRED",
                        "구성원 초대가 만료되었습니다"
                ),
                HttpStatus.GONE
        );
    }

    @DisplayName("구성원 초대 발급 API는 최초 요청과 동일 멱등 재생을 구분한다")
    @Test
    void documentsMemberInvitationIssueAndReplay() throws Exception {
        when(memberInvitationUseCase.issue(
                account(),
                IDEMPOTENCY_KEY,
                issueCommand()
        )).thenReturn(issued(false), issued(true));

        performIssue()
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.token").value(INVITATION_TOKEN))
                .andDo(document(
                        "issueMemberInvitation",
                        ISSUE_MEMBER_INVITATION,
                        teamPath(),
                        issueMutationHeaders(),
                        requestFields(issueRequestField()),
                        noStoreResponseHeader(),
                        responseFields(issueFields())
                ));

        performIssue()
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.invitationId").value(INVITATION_ID.toString()))
                .andDo(document(
                        "issueMemberInvitationReplay",
                        ISSUE_MEMBER_INVITATION,
                        teamPath(),
                        issueMutationHeaders(),
                        requestFields(issueRequestField()),
                        noStoreResponseHeader(),
                        responseFields(issueFields())
                ));
    }

    @DisplayName("구성원 초대 발급 API는 보안·대상·설정 오류를 구분한다")
    @Test
    void documentsMemberInvitationIssueErrors() throws Exception {
        mockMvc.perform(post("/api/v1/teams/{teamId}/member-invitations", TEAM_ID)
                        .with(authentication(accountAuthentication()))
                        .with(csrf().asHeader())
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andDo(document(
                        "issueMemberInvitationInvalidInput",
                        ISSUE_MEMBER_INVITATION,
                        teamPath(),
                        issueHeaders(),
                        noStoreResponseHeader(),
                        responseFields(errorFields())
                ));

        mockMvc.perform(post("/api/v1/teams/{teamId}/member-invitations", TEAM_ID)
                        .with(csrf().asHeader())
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(issueRequest()))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andDo(document(
                        "issueMemberInvitationAuthenticationRequired",
                        ISSUE_MEMBER_INVITATION,
                        teamPath(),
                        issueHeaders(),
                        noStoreResponseHeader(),
                        responseFields(errorFields())
                ));

        mockMvc.perform(post("/api/v1/teams/{teamId}/member-invitations", TEAM_ID)
                        .with(authentication(accountAuthentication()))
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(issueRequest()))
                .andExpect(status().isForbidden())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andDo(document(
                        "issueMemberInvitationCsrfInvalid",
                        ISSUE_MEMBER_INVITATION,
                        teamPath(),
                        issueHeaders(),
                        noStoreResponseHeader(),
                        responseFields(errorFields())
                ));

        documentIssueApplicationError(
                "issueMemberInvitationOwnerForbidden",
                new IdentityOperationException(
                        "MEMBER_INVITATION_FORBIDDEN",
                        "구성원 초대를 관리할 권한이 없습니다"
                ),
                HttpStatus.FORBIDDEN
        );
        documentIssueApplicationError(
                "issueMemberInvitationNotFound",
                new IdentityNotFoundException(
                        "MEMBER_NOT_FOUND",
                        "구성원을 찾을 수 없습니다"
                ),
                HttpStatus.NOT_FOUND
        );
        documentIssueApplicationError(
                "issueMemberInvitationConflict",
                new IdentityOperationException(
                        "MEMBER_INVITATION_TARGET_UNAVAILABLE",
                        "구성원 초대 대상을 사용할 수 없습니다"
                ),
                HttpStatus.CONFLICT
        );
        documentIssueApplicationError(
                "issueMemberInvitationUnavailable",
                new IdentityOperationException(
                        "MEMBER_INVITATION_CONFIGURATION_INVALID",
                        "구성원 초대 발급 설정이 안전하지 않습니다"
                ),
                HttpStatus.SERVICE_UNAVAILABLE
        );
    }

    @DisplayName("열린 구성원 초대 목록 API는 원문 토큰 없이 만료 전 초대만 반환한다")
    @Test
    void documentsMemberInvitationList() throws Exception {
        when(memberInvitationUseCase.listOpen(TEAM_ID, account()))
                .thenReturn(List.of(new OpenMemberInvitation(
                        INVITATION_ID,
                        TEAM_ID,
                        MEMBER_ID,
                        ISSUED_AT,
                        EXPIRES_AT
                )));

        mockMvc.perform(get("/api/v1/teams/{teamId}/member-invitations", TEAM_ID)
                        .with(authentication(accountAuthentication())))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$[0].token").doesNotExist())
                .andDo(document(
                        "listMemberInvitations",
                        LIST_MEMBER_INVITATIONS,
                        teamPath(),
                        noStoreResponseHeader(),
                        responseFields(listFields())
                ));
    }

    @DisplayName("열린 구성원 초대 목록 API는 인증과 OWNER 권한을 요구한다")
    @Test
    void documentsMemberInvitationListErrors() throws Exception {
        mockMvc.perform(get("/api/v1/teams/{teamId}/member-invitations", TEAM_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andDo(document(
                        "listMemberInvitationsAuthenticationRequired",
                        LIST_MEMBER_INVITATIONS,
                        teamPath(),
                        noStoreResponseHeader(),
                        responseFields(errorFields())
                ));

        when(memberInvitationUseCase.listOpen(TEAM_ID, account()))
                .thenThrow(new IdentityOperationException(
                        "MEMBER_INVITATION_FORBIDDEN",
                        "구성원 초대를 관리할 권한이 없습니다"
                ));
        mockMvc.perform(get("/api/v1/teams/{teamId}/member-invitations", TEAM_ID)
                        .with(authentication(accountAuthentication())))
                .andExpect(status().isForbidden())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andDo(document(
                        "listMemberInvitationsForbidden",
                        LIST_MEMBER_INVITATIONS,
                        teamPath(),
                        noStoreResponseHeader(),
                        responseFields(errorFields())
                ));
    }

    @DisplayName("구성원 초대 폐기 API는 폐기 시각을 반환한다")
    @Test
    void documentsMemberInvitationRevocation() throws Exception {
        when(memberInvitationUseCase.revoke(
                TEAM_ID,
                INVITATION_ID,
                account()
        )).thenReturn(new RevokedMemberInvitation(INVITATION_ID, ISSUED_AT));

        performRevocation()
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.revokedAt").value(ISSUED_AT.toString()))
                .andDo(document(
                        "revokeMemberInvitation",
                        REVOKE_MEMBER_INVITATION,
                        invitationPath(),
                        csrfHeader(),
                        noStoreResponseHeader(),
                        responseFields(
                                fieldWithPath("invitationId")
                                        .description("폐기한 구성원 초대 UUID"),
                                fieldWithPath("revokedAt")
                                        .description("초대를 폐기한 UTC 시각")
                        )
                ));
    }

    @DisplayName("구성원 초대 폐기 API는 인증·권한·상태 오류를 구분한다")
    @Test
    void documentsMemberInvitationRevocationErrors() throws Exception {
        mockMvc.perform(post(
                        "/api/v1/teams/{teamId}/member-invitations/"
                                + "{invitationId}/revocation",
                        TEAM_ID,
                        "not-a-uuid"
                )
                        .with(authentication(accountAuthentication()))
                        .with(csrf().asHeader()))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andDo(document(
                        "revokeMemberInvitationInvalidInput",
                        REVOKE_MEMBER_INVITATION,
                        invitationPath(),
                        csrfHeader(),
                        noStoreResponseHeader(),
                        responseFields(errorFields())
                ));

        mockMvc.perform(post(
                        "/api/v1/teams/{teamId}/member-invitations/"
                                + "{invitationId}/revocation",
                        TEAM_ID,
                        INVITATION_ID
                )
                        .with(csrf().asHeader()))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andDo(document(
                        "revokeMemberInvitationAuthenticationRequired",
                        REVOKE_MEMBER_INVITATION,
                        invitationPath(),
                        csrfHeader(),
                        noStoreResponseHeader(),
                        responseFields(errorFields())
                ));

        mockMvc.perform(post(
                        "/api/v1/teams/{teamId}/member-invitations/"
                                + "{invitationId}/revocation",
                        TEAM_ID,
                        INVITATION_ID
                )
                        .with(authentication(accountAuthentication())))
                .andExpect(status().isForbidden())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andDo(document(
                        "revokeMemberInvitationCsrfInvalid",
                        REVOKE_MEMBER_INVITATION,
                        invitationPath(),
                        optionalCsrfHeader(),
                        noStoreResponseHeader(),
                        responseFields(errorFields())
                ));

        documentRevocationApplicationError(
                "revokeMemberInvitationForbidden",
                new IdentityOperationException(
                        "MEMBER_INVITATION_FORBIDDEN",
                        "구성원 초대를 관리할 권한이 없습니다"
                ),
                HttpStatus.FORBIDDEN
        );
        documentRevocationApplicationError(
                "revokeMemberInvitationNotFound",
                new IdentityOperationException(
                        "MEMBER_INVITATION_NOT_FOUND",
                        "구성원 초대를 찾을 수 없습니다"
                ),
                HttpStatus.NOT_FOUND
        );
        documentRevocationApplicationError(
                "revokeMemberInvitationConflict",
                new IdentityOperationException(
                        "MEMBER_INVITATION_USED",
                        "구성원 초대가 이미 사용되었습니다"
                ),
                HttpStatus.CONFLICT
        );
        documentRevocationApplicationError(
                "revokeMemberInvitationExpired",
                new IdentityOperationException(
                        "MEMBER_INVITATION_EXPIRED",
                        "구성원 초대가 만료되었습니다"
                ),
                HttpStatus.GONE
        );
    }

    private void documentPreviewApplicationError(
            String identifier,
            RuntimeException exception,
            HttpStatus expectedStatus
    ) throws Exception {
        reset(invitationAcceptanceUseCase);
        when(invitationAcceptanceUseCase.preview(INVITATION_TOKEN, account()))
                .thenThrow(exception);
        mockMvc.perform(post("/api/v1/identity/invitations/preview")
                        .with(authentication(accountAuthentication()))
                        .with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tokenRequest()))
                .andExpect(status().is(expectedStatus.value()))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andDo(document(
                        identifier,
                        PREVIEW_INVITATION,
                        csrfHeader(),
                        noStoreResponseHeader(),
                        responseFields(errorFields())
                ));
    }

    private void documentIssueApplicationError(
            String identifier,
            RuntimeException exception,
            HttpStatus expectedStatus
    ) throws Exception {
        reset(memberInvitationUseCase);
        when(memberInvitationUseCase.issue(
                account(),
                IDEMPOTENCY_KEY,
                issueCommand()
        )).thenThrow(exception);
        performIssue()
                .andExpect(status().is(expectedStatus.value()))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andDo(document(
                        identifier,
                        ISSUE_MEMBER_INVITATION,
                        teamPath(),
                        issueHeaders(),
                        noStoreResponseHeader(),
                        responseFields(errorFields())
                ));
    }

    private void documentRevocationApplicationError(
            String identifier,
            RuntimeException exception,
            HttpStatus expectedStatus
    ) throws Exception {
        reset(memberInvitationUseCase);
        when(memberInvitationUseCase.revoke(TEAM_ID, INVITATION_ID, account()))
                .thenThrow(exception);
        performRevocation()
                .andExpect(status().is(expectedStatus.value()))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andDo(document(
                        identifier,
                        REVOKE_MEMBER_INVITATION,
                        invitationPath(),
                        csrfHeader(),
                        noStoreResponseHeader(),
                        responseFields(errorFields())
                ));
    }

    private org.springframework.test.web.servlet.ResultActions performIssue()
            throws Exception {
        return mockMvc.perform(post(
                        "/api/v1/teams/{teamId}/member-invitations",
                        TEAM_ID
                )
                .with(authentication(accountAuthentication()))
                .with(csrf().asHeader())
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(issueRequest()));
    }

    private org.springframework.test.web.servlet.ResultActions performRevocation()
            throws Exception {
        return mockMvc.perform(post(
                        "/api/v1/teams/{teamId}/member-invitations/"
                                + "{invitationId}/revocation",
                        TEAM_ID,
                        INVITATION_ID
                )
                .with(authentication(accountAuthentication()))
                .with(csrf().asHeader()));
    }

    private AuthenticatedAccount account() {
        return new AuthenticatedAccount(ACCOUNT_ID);
    }

    private IssueMemberInvitationCommand issueCommand() {
        return new IssueMemberInvitationCommand(TEAM_ID, MEMBER_ID);
    }

    private IssuedMemberInvitation issued(boolean replayed) {
        return new IssuedMemberInvitation(
                INVITATION_ID,
                TEAM_ID,
                MEMBER_ID,
                INVITATION_TOKEN,
                ISSUED_AT,
                EXPIRES_AT,
                replayed
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

    private String tokenRequest() {
        return "{\"token\":\"" + INVITATION_TOKEN + "\"}";
    }

    private String issueRequest() {
        return "{\"memberId\":\"" + MEMBER_ID + "\"}";
    }

    private FieldDescriptor tokenRequestField() {
        return constrainedField(
                IdentityRequests.AcceptInvitationRequest.class,
                "token",
                "URL·header·저장소가 아닌 JSON 본문으로만 전달하는 일회성 초대 토큰"
        );
    }

    private FieldDescriptor issueRequestField() {
        return constrainedField(
                IdentityRequests.IssueMemberInvitationRequest.class,
                "memberId",
                "로그인 계정과 결속할 기존 활성 구성원 UUID"
        );
    }

    private FieldDescriptor constrainedField(
            Class<?> requestType,
            String path,
            String description
    ) {
        return new ConstrainedFields(requestType)
                .withPath(path)
                .description(description);
    }

    private FieldDescriptor[] membershipFields() {
        return new FieldDescriptor[]{
                fieldWithPath("accountId").description("로그인한 BATON 내부 계정 UUID"),
                fieldWithPath("teamId").description("결속된 팀 UUID"),
                fieldWithPath("memberId").description("결속된 활성 구성원 UUID"),
                fieldWithPath("boundAt").description("신원을 결속한 UTC 시각"),
                new EnumFields(MemberIdentityRole.class)
                        .withPath("role")
                        .description("현재 팀 신원 역할")
        };
    }

    private FieldDescriptor[] previewFields() {
        return new FieldDescriptor[]{
                fieldWithPath("teamId").description("초대 대상 팀 UUID"),
                fieldWithPath("teamName").description("확인용 팀 이름"),
                fieldWithPath("memberId").description("초대 대상 구성원 UUID"),
                fieldWithPath("memberName").description("확인용 구성원 이름"),
                new EnumFields(MemberIdentityRole.class)
                        .withPath("role")
                        .description("수락하면 부여될 팀 신원 역할"),
                fieldWithPath("expiresAt").description("초대 만료 UTC 시각"),
                fieldWithPath("alreadyAccepted")
                        .description("현재 계정이 이미 이 초대를 수락했는지 여부")
        };
    }

    private FieldDescriptor[] issueFields() {
        return new FieldDescriptor[]{
                fieldWithPath("invitationId").description("발급한 구성원 초대 UUID"),
                fieldWithPath("teamId").description("초대 대상 팀 UUID"),
                fieldWithPath("memberId").description("초대 대상 구성원 UUID"),
                fieldWithPath("token")
                        .description("최초 성공과 동일 멱등 재생에서 반환하는 원문 초대 토큰"),
                fieldWithPath("issuedAt").description("초대 발급 UTC 시각"),
                fieldWithPath("expiresAt").description("초대 만료 UTC 시각")
        };
    }

    private FieldDescriptor[] listFields() {
        return new FieldDescriptor[]{
                fieldWithPath("[]").description("열린 구성원 초대 목록"),
                fieldWithPath("[].invitationId").description("구성원 초대 UUID"),
                fieldWithPath("[].teamId").description("초대 대상 팀 UUID"),
                fieldWithPath("[].memberId").description("초대 대상 구성원 UUID"),
                fieldWithPath("[].issuedAt").description("초대 발급 UTC 시각"),
                fieldWithPath("[].expiresAt").description("초대 만료 UTC 시각")
        };
    }

    private FieldDescriptor[] errorFields() {
        return new FieldDescriptor[]{
                fieldWithPath("code").description("안정적인 오류 코드"),
                fieldWithPath("message").description("사용자에게 표시할 오류 설명")
        };
    }

    private Snippet teamPath() {
        return pathParameters(parameterWithName("teamId").description("팀 UUID"));
    }

    private Snippet invitationPath() {
        return pathParameters(
                parameterWithName("teamId").description("팀 UUID"),
                parameterWithName("invitationId").description("구성원 초대 UUID")
        );
    }

    private Snippet issueHeaders() {
        return requestHeaders(headerWithName("Idempotency-Key")
                .description("동일 발급 의도를 재시도하는 canonical UUID"));
    }

    private Snippet issueMutationHeaders() {
        return requestHeaders(
                headerWithName("Idempotency-Key")
                        .description("동일 발급 의도를 재시도하는 canonical UUID"),
                headerWithName("X-CSRF-TOKEN")
                        .description("현재 인증 세션에 결속된 CSRF 토큰")
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

    private record OperationDocumentation(String summary, String description) {
    }
}
