package com.personal.baton.adapter.in.web.restdocs;

import com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper;
import com.epages.restdocs.apispec.EnumFields;
import com.epages.restdocs.apispec.ConstrainedFields;
import com.personal.baton.adapter.in.web.GlobalExceptionHandler;
import com.personal.baton.adapter.in.web.auth.AuthenticatedAccountPrincipal;
import com.personal.baton.adapter.in.web.workspace.TeamAccessController;
import com.personal.baton.adapter.in.web.workspace.TeamAccessRequests.ActivateTeamAccessRequest;
import com.personal.baton.adapter.in.web.workspace.TeamAccessRequests.CreateTeamInvitationRequest;
import com.personal.baton.adapter.in.web.workspace.TeamAccessRequests.ChangeTeamPermissionRequest;
import com.personal.baton.adapter.in.web.workspace.TeamAccessRequests.ExpectedAccountRequest;
import com.personal.baton.adapter.in.web.workspace.TeamAccessRequests.TeamInvitationTokenRequest;
import com.personal.baton.application.workspace.port.in.TeamAccessUseCase;
import com.personal.baton.application.workspace.port.in.TeamAccessUseCase.*;
import com.personal.baton.domain.workspace.TeamPermission;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import org.springframework.restdocs.headers.HeaderDescriptor;
import org.springframework.restdocs.headers.RequestHeadersSnippet;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.http.MediaType;
import org.springframework.restdocs.RestDocumentationContextProvider;
import org.springframework.restdocs.RestDocumentationExtension;
import org.springframework.restdocs.payload.JsonFieldType;
import org.springframework.restdocs.payload.ResponseFieldsSnippet;
import org.springframework.restdocs.payload.RequestFieldsSnippet;
import org.springframework.restdocs.request.PathParametersSnippet;
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
import static org.springframework.restdocs.request.RequestDocumentation.*;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.documentationConfiguration;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Tag("restdocs")
@ExtendWith(RestDocumentationExtension.class)
class TeamAccessRestDocsTest {
    private static final UUID TEAM = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID SEASON = UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final UUID RESOURCE = UUID.fromString("00000000-0000-4000-8000-000000000003");
    private static final UUID ACCOUNT = UUID.fromString("00000000-0000-4000-8000-000000000004");
    private MockMvc mvc;
    private TeamAccessUseCase useCase;

    @BeforeEach
    void setUp(RestDocumentationContextProvider documentation) {
        useCase = mock(TeamAccessUseCase.class);
        when(useCase.getAccess(any(), any(), any())).thenReturn(settings());
        when(useCase.activate(any(), any(), any(), any())).thenReturn(settings());
        when(useCase.revokeInvitation(any(), any(), any())).thenReturn(settings());
        when(useCase.changePermission(any(), any(), any(), any())).thenReturn(settings());
        mvc = MockMvcBuilders.standaloneSetup(new TeamAccessController(useCase))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .apply(springSecurity(new FilterChainProxy(new DefaultSecurityFilterChain(AnyRequestMatcher.INSTANCE,
                        new SecurityContextHolderFilter(new HttpSessionSecurityContextRepository())))))
                .apply(documentationConfiguration(documentation)).build();
    }

    @Test @DisplayName("팀 접근 설정은 내 권한과 관리 가능한 구성원·초대·변경 이력을 반환한다")
    void documentsAccess() throws Exception {
        mvc.perform(get(TeamAccessController.PATH, TEAM).header("X-Baton-Access-Key", "key").with(authentication(auth())))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andDo(MockMvcRestDocumentationWrapper.document("getTeamAccess", "팀의 접근 방식과 현재 계정의 권한을 조회한다.", "팀 접근 설정 조회",
                        teamPath(), requestHeaders(headerWithName("X-Baton-Access-Key").description("공유 키 팀에서 필요한 접근 키").optional()),
                        responseHeaders(headerWithName("Cache-Control").description("비공개 응답 캐시 금지")), settingsFields()));
    }
    @Test @DisplayName("계정 권한 전환과 관리자 복구는 운영 복구 키와 연결된 본인 구성원을 요구한다")
    void documentsActivation() throws Exception {
        mvc.perform(post(TeamAccessController.PATH + "/activate", TEAM).with(authentication(auth())).header("Origin", "https://baton.example").header("X-CSRF-TOKEN", "csrf-token")
                        .header("X-Baton-Recovery-Key", "operator-recovery-key").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedAccountId\":\"" + ACCOUNT + "\",\"memberId\":\"" + RESOURCE + "\"}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andDo(MockMvcRestDocumentationWrapper.document("activateTeamAccountAccess", "운영 복구 키로 현재 계정의 구성원을 관리자로 지정하고 공유 키 접근을 닫는다.", "팀 계정 권한 전환 및 관리자 복구",
                        teamPath(), accountMutationHeaders(headerWithName("X-Baton-Recovery-Key").description("운영자 복구 키")),
                        requestFields(new ConstrainedFields(ActivateTeamAccessRequest.class).withPath("expectedAccountId").description("현재 로그인 계정"),
                                new ConstrainedFields(ActivateTeamAccessRequest.class).withPath("memberId").description("현재 계정과 연결된 구성원")),
                        responseHeaders(headerWithName("Cache-Control").description("비공개 응답 캐시 금지")), settingsFields()));
    }
    @Test @DisplayName("관리자는 만료 기한이 있는 일회용 초대 토큰을 만든다")
    void documentsInvitation() throws Exception {
        when(useCase.invite(any(), any(), any(), any())).thenReturn(new CreatedInvitationResult(invitation(), "a".repeat(43)));
        mvc.perform(post(TeamAccessController.PATH + "/invitations", TEAM).with(authentication(auth())).header("Origin", "https://baton.example").header("X-CSRF-TOKEN", "csrf-token").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedAccountId\":\"" + ACCOUNT + "\",\"memberId\":\"" + RESOURCE + "\",\"permission\":\"VIEWER\"}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andDo(MockMvcRestDocumentationWrapper.document("createTeamInvitation", "관리자가 기존 구성원의 7일 유효 초대를 만들고 원문 토큰은 이 응답에서만 제공한다.", "팀 초대 생성",
                        teamPath(), accountMutationHeaders(), requestFields(new ConstrainedFields(CreateTeamInvitationRequest.class).withPath("expectedAccountId").description("현재 로그인 계정"),
                                new ConstrainedFields(CreateTeamInvitationRequest.class).withPath("memberId").description("초대할 기존 구성원"),
                                new EnumFields(TeamPermission.class).withPath("permission").description("초대 권한")),
                        responseHeaders(headerWithName("Cache-Control").description("초대 토큰 캐시 금지")),
                        responseFields(fieldWithPath("token").description("43자 일회용 초대 토큰"),
                                fieldWithPath("invitation.id").description("초대 식별자"), fieldWithPath("invitation.memberId").description("초대할 구성원"),
                                new EnumFields(TeamPermission.class).withPath("invitation.permission").description("초대 권한"),
                                fieldWithPath("invitation.createdAt").description("생성 시각"), fieldWithPath("invitation.expiresAt").description("만료 시각"),
                                fieldWithPath("invitation.acceptedAt").type(JsonFieldType.STRING).optional().description("수락 시각"),
                                fieldWithPath("invitation.revokedAt").type(JsonFieldType.STRING).optional().description("취소 시각"))));
    }
    @Test @DisplayName("관리자는 미수락 초대를 취소한다")
    void documentsRevocation() throws Exception {
        mvc.perform(post(TeamAccessController.PATH + "/invitations/{invitationId}/revoke", TEAM, RESOURCE).with(authentication(auth())).header("Origin", "https://baton.example").header("X-CSRF-TOKEN", "csrf-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedAccountId\":\"" + ACCOUNT + "\"}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andDo(MockMvcRestDocumentationWrapper.document("revokeTeamInvitation", "미수락 초대를 취소한다.", "팀 초대 취소",
                        pathParameters(parameterWithName("teamId").description("팀 식별자"), parameterWithName("invitationId").description("초대 식별자")),
                        accountMutationHeaders(), requestFields(new ConstrainedFields(ExpectedAccountRequest.class).withPath("expectedAccountId").description("현재 로그인 계정")),
                        responseHeaders(headerWithName("Cache-Control").description("비공개 응답 캐시 금지")), settingsFields()));
    }
    @Test @DisplayName("관리자는 구성원 권한을 바꾸거나 접근을 취소한다")
    void documentsPermission() throws Exception {
        mvc.perform(put(TeamAccessController.PATH + "/members/{memberId}/permission", TEAM, RESOURCE).with(authentication(auth())).header("Origin", "https://baton.example").header("X-CSRF-TOKEN", "csrf-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedAccountId\":\"" + ACCOUNT + "\",\"permission\":\"VIEWER\"}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andDo(MockMvcRestDocumentationWrapper.document("changeTeamPermission", "구성원 권한을 변경한다. null은 접근 취소이고 마지막 활성 관리자는 취소할 수 없다.", "팀 구성원 권한 변경",
                        pathParameters(parameterWithName("teamId").description("팀 식별자"), parameterWithName("memberId").description("구성원 식별자")),
                        accountMutationHeaders(), requestFields(new ConstrainedFields(ChangeTeamPermissionRequest.class).withPath("expectedAccountId").description("현재 로그인 계정"),
                                new EnumFields(TeamPermission.class).withPath("permission").optional().description("새 권한, null은 접근 취소")),
                        responseHeaders(headerWithName("Cache-Control").description("비공개 응답 캐시 금지")), settingsFields()));
    }
    @Test @DisplayName("초대 수락 전 팀과 구성원 및 권한을 확인한다")
    void documentsPreview() throws Exception {
        when(useCase.preview(any(), any())).thenReturn(new InvitationPreviewResult(TEAM, "운영 팀", RESOURCE, "박민서", TeamPermission.VIEWER, NOW.plusSeconds(604800)));
        mvc.perform(post(TeamAccessController.INVITATION_PATH + "/preview").with(authentication(auth())).header("Origin", "https://baton.example").header("X-CSRF-TOKEN", "csrf-token")
                        .contentType(MediaType.APPLICATION_JSON).content(tokenBody()))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andDo(MockMvcRestDocumentationWrapper.document("previewTeamInvitation", "로그인한 계정이 초대의 팀·구성원·권한과 만료 시각을 확인한다.", "팀 초대 확인",
                        accountMutationHeaders(), tokenFields(), responseHeaders(headerWithName("Cache-Control").description("비공개 응답 캐시 금지")),
                        responseFields(fieldWithPath("teamId").description("팀 식별자"), fieldWithPath("teamName").description("팀 이름"),
                                fieldWithPath("memberId").description("구성원 식별자"), fieldWithPath("memberName").description("구성원 이름"),
                                new EnumFields(TeamPermission.class).withPath("permission").description("초대 권한"),
                                fieldWithPath("expiresAt").description("만료 시각"))));
    }
    @Test @DisplayName("초대 수락은 현재 계정과 구성원을 연결하고 이동할 시즌을 반환한다")
    void documentsAcceptance() throws Exception {
        when(useCase.accept(any(), any())).thenReturn(new InvitationAcceptedResult(ACCOUNT, TEAM, SEASON, RESOURCE, TeamPermission.VIEWER));
        mvc.perform(post(TeamAccessController.INVITATION_PATH + "/accept").with(authentication(auth())).header("Origin", "https://baton.example").header("X-CSRF-TOKEN", "csrf-token")
                        .contentType(MediaType.APPLICATION_JSON).content(tokenBody()))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andDo(MockMvcRestDocumentationWrapper.document("acceptTeamInvitation", "같은 초대는 같은 계정만 다시 확인할 수 있고 재수락으로 폐기된 권한을 복원하지 않는다.", "팀 초대 수락",
                        accountMutationHeaders(), tokenFields(), responseHeaders(headerWithName("Cache-Control").description("비공개 응답 캐시 금지")),
                        responseFields(fieldWithPath("accountId").description("수락 계정"), fieldWithPath("teamId").description("팀 식별자"),
                                fieldWithPath("seasonId").description("이동할 최신 시즌"), fieldWithPath("memberId").description("연결된 구성원"),
                                new EnumFields(TeamPermission.class).withPath("permission").description("현재 권한"))));
    }
    private static final Instant NOW = Instant.parse("2026-09-05T03:00:00Z");
    private UsernamePasswordAuthenticationToken auth() { return UsernamePasswordAuthenticationToken.authenticated(new Principal(ACCOUNT, 0), null, List.of()); }
    private record Principal(UUID accountId, long sessionVersion) implements AuthenticatedAccountPrincipal {}
    private String tokenBody() { return "{\"expectedAccountId\":\"" + ACCOUNT + "\",\"token\":\"" + "a".repeat(43) + "\"}"; }
    private RequestFieldsSnippet tokenFields() {
        return requestFields(new ConstrainedFields(TeamInvitationTokenRequest.class).withPath("expectedAccountId").description("현재 로그인 계정"),
                new ConstrainedFields(TeamInvitationTokenRequest.class).withPath("token").description("43자 초대 토큰"));
    }
    private RequestHeadersSnippet accountMutationHeaders(HeaderDescriptor... extra) {
        List<HeaderDescriptor> headers = new ArrayList<>(List.of(extra));
        headers.add(headerWithName("Origin").description("BATON과 동일한 출처"));
        headers.add(headerWithName("X-CSRF-TOKEN").description("현재 세션의 CSRF 토큰"));
        return requestHeaders(headers);
    }
    private PathParametersSnippet teamPath() { return pathParameters(parameterWithName("teamId").description("팀 식별자")); }
    private InvitationResult invitation() { return new InvitationResult(RESOURCE, RESOURCE, TeamPermission.VIEWER, NOW, NOW.plusSeconds(604800), null, null); }
    private TeamAccessResult settings() {
        return new TeamAccessResult(TEAM, ACCOUNT, true, RESOURCE, TeamPermission.ADMIN,
                List.of(new MemberAccessResult(RESOURCE, "박민서", true, ACCOUNT, TeamPermission.ADMIN)), List.of(invitation()),
                List.of(new AccessAuditResult(RESOURCE, ACCOUNT, RESOURCE, "ADMIN_RECOVERY", null, TeamPermission.ADMIN, NOW)));
    }
    private ResponseFieldsSnippet settingsFields() {
        return responseFields(fieldWithPath("teamId").description("팀 식별자"), fieldWithPath("accountId").description("현재 계정"),
                fieldWithPath("accountAccessEnabled").description("계정 권한 전환 여부"), fieldWithPath("memberId").optional().description("내 연결 구성원"),
                new EnumFields(TeamPermission.class).withPath("permission").optional().description("내 권한"),
                fieldWithPath("members").type(JsonFieldType.ARRAY).description("관리 가능한 구성원"),
                fieldWithPath("members[].memberId").description("구성원 식별자"), fieldWithPath("members[].memberName").description("구성원 이름"),
                fieldWithPath("members[].active").description("활동 여부"), fieldWithPath("members[].accountId").optional().description("연결 계정"),
                new EnumFields(TeamPermission.class).withPath("members[].permission").optional().description("승인된 권한"),
                fieldWithPath("invitations").type(JsonFieldType.ARRAY).description("초대 목록, 관리자에게만 제공"),
                fieldWithPath("invitations[].id").description("초대 식별자"), fieldWithPath("invitations[].memberId").description("초대 구성원"),
                new EnumFields(TeamPermission.class).withPath("invitations[].permission").description("초대 권한"),
                fieldWithPath("invitations[].createdAt").description("생성 시각"), fieldWithPath("invitations[].expiresAt").description("만료 시각"),
                fieldWithPath("invitations[].acceptedAt").type(JsonFieldType.STRING).optional().description("수락 시각"),
                fieldWithPath("invitations[].revokedAt").type(JsonFieldType.STRING).optional().description("취소 시각"),
                fieldWithPath("audit").type(JsonFieldType.ARRAY).description("최근 접근 변경 50건, 관리자에게만 제공"),
                fieldWithPath("audit[].id").description("변경 이력 식별자"), fieldWithPath("audit[].actorAccountId").description("변경 계정"),
                fieldWithPath("audit[].memberId").description("대상 구성원"), fieldWithPath("audit[].action").description("변경 종류"),
                new EnumFields(TeamPermission.class).withPath("audit[].previousPermission").optional().description("이전 권한"),
                new EnumFields(TeamPermission.class).withPath("audit[].permission").optional().description("변경 뒤 권한"),
                fieldWithPath("audit[].changedAt").description("변경 시각"));
    }
}
