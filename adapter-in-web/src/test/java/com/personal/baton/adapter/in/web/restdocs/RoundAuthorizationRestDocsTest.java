package com.personal.baton.adapter.in.web.restdocs;

import com.epages.restdocs.apispec.ConstrainedFields;
import com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper;
import com.personal.baton.adapter.in.web.RequestIdFilter;
import com.personal.baton.adapter.in.web.auth.AuthenticatedAccountPrincipal;
import com.personal.baton.adapter.in.web.roundauth.ParticipationGrantController;
import com.personal.baton.adapter.in.web.roundauth.RoundAdministrationController;
import com.personal.baton.adapter.in.web.roundauth.RoundAdministrationRequests;
import com.personal.baton.application.roundauth.port.in.ReadParticipationGrantJwkSetUseCase;
import com.personal.baton.application.roundauth.port.in.RoundAuthorizationUseCase;
import com.personal.baton.application.roundauth.port.in.RoundAuthorizationUseCase.MembershipResult;
import com.personal.baton.application.roundauth.port.in.RoundAuthorizationUseCase.ParticipationGrantResult;
import com.personal.baton.application.roundauth.port.in.RoundAuthorizationUseCase.RoomMappingResult;
import jakarta.validation.constraints.NotNull;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.restdocs.RestDocumentationContextProvider;
import org.springframework.restdocs.RestDocumentationExtension;
import org.springframework.restdocs.payload.FieldDescriptor;
import org.springframework.restdocs.payload.JsonFieldType;
import org.springframework.restdocs.snippet.Snippet;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.restdocs.headers.HeaderDocumentation.headerWithName;
import static org.springframework.restdocs.headers.HeaderDocumentation.requestHeaders;
import static org.springframework.restdocs.headers.HeaderDocumentation.responseHeaders;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.documentationConfiguration;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.delete;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.get;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.post;
import static org.springframework.restdocs.operation.preprocess.Preprocessors.prettyPrint;
import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;
import static org.springframework.restdocs.payload.PayloadDocumentation.requestFields;
import static org.springframework.restdocs.payload.PayloadDocumentation.responseFields;
import static org.springframework.restdocs.request.RequestDocumentation.parameterWithName;
import static org.springframework.restdocs.request.RequestDocumentation.pathParameters;
import static org.springframework.restdocs.request.RequestDocumentation.queryParameters;
import static org.springframework.restdocs.snippet.Attributes.key;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Tag("restdocs")
@ExtendWith(RestDocumentationExtension.class)
class RoundAuthorizationRestDocsTest {

    private static final UUID REQUEST_ID =
            UUID.fromString("11111111-2222-4333-8444-555555555555");
    private static final UUID ACCOUNT_ID =
            UUID.fromString("8e448211-66ae-44ab-9888-c4960648c22b");
    private static final UUID TEAM_ID =
            UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID SEASON_ID =
            UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID MEMBER_ID =
            UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID RESOURCE_ID =
            UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final String ROOM_ID = "bcdf-ghjk-mnpq";
    private static final String ACCESS_KEY = "workspace-access-key";
    private static final String ORIGIN = "https://baton.example";
    private static final String CSRF_HEADER = "X-CSRF-TOKEN";
    private static final String CSRF_TOKEN = "opaque-csrf-token";
    private static final Instant NOW = Instant.parse("2026-08-08T12:34:56Z");
    private static final String CURRENT_MEMBERSHIP_DESCRIPTION =
            "인증된 BATON 계정과 현재 팀의 기존 구성원 연결 상태를 workspace 접근 키로 조회한다.";
    private static final String CURRENT_MEMBERSHIP_SUMMARY =
            "현재 계정 구성원 연결 조회";

    private RoundAuthorizationUseCase roundAuthorizationUseCase;
    private ReadParticipationGrantJwkSetUseCase readJwkSetUseCase;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp(RestDocumentationContextProvider restDocumentation) {
        roundAuthorizationUseCase = mock(RoundAuthorizationUseCase.class);
        readJwkSetUseCase = mock(ReadParticipationGrantJwkSetUseCase.class);
        ParticipationGrantController participationGrantController =
                new ParticipationGrantController(
                        roundAuthorizationUseCase,
                        readJwkSetUseCase,
                        Clock.fixed(NOW, ZoneOffset.UTC)
                );
        RoundAdministrationController administrationController =
                new RoundAdministrationController(roundAuthorizationUseCase);

        mockMvc = MockMvcBuilders.standaloneSetup(
                        participationGrantController,
                        administrationController
                )
                .addFilters(new RequestIdFilter(() -> REQUEST_ID))
                .apply(documentationConfiguration(restDocumentation)
                        .operationPreprocessors()
                        .withRequestDefaults(prettyPrint())
                        .withResponseDefaults(prettyPrint()))
                .build();
    }

    @DisplayName("현재 membership 조회 API는 연결되지 않은 계정을 정상 상태로 반환한다")
    @Test
    void documentsUnclaimedCurrentMembership() throws Exception {
        when(roundAuthorizationUseCase.findCurrentMembership(any()))
                .thenReturn(Optional.empty());

        mockMvc.perform(get(RoundAdministrationController.CURRENT_MEMBERSHIP_PATH)
                        .param("teamId", TEAM_ID.toString())
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .principal(authentication()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(RequestIdFilter.HEADER_NAME, REQUEST_ID.toString()))
                .andExpect(content().json("{\"claimed\":false}", true))
                .andDo(MockMvcRestDocumentationWrapper.document(
                        "getCurrentAccountMembership",
                        CURRENT_MEMBERSHIP_DESCRIPTION,
                        CURRENT_MEMBERSHIP_SUMMARY,
                        queryParameters(
                                parameterWithName("teamId")
                                        .description("연결 상태를 확인할 팀 UUID")
                        ),
                        membershipReadHeaders(),
                        noStoreResponseHeaders(),
                        responseFields(
                                fieldWithPath("claimed")
                                        .description("항상 false인 미연결 상태 표시")
                        )));
    }

    @DisplayName("현재 membership 조회 API는 연결된 계정과 구성원 snapshot을 반환한다")
    @Test
    void documentsClaimedCurrentMembership() throws Exception {
        when(roundAuthorizationUseCase.findCurrentMembership(any()))
                .thenReturn(Optional.of(new MembershipResult(
                        ACCOUNT_ID,
                        TEAM_ID,
                        MEMBER_ID,
                        NOW
                )));

        mockMvc.perform(get(RoundAdministrationController.CURRENT_MEMBERSHIP_PATH)
                        .param("teamId", TEAM_ID.toString())
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .principal(authentication()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(RequestIdFilter.HEADER_NAME, REQUEST_ID.toString()))
                .andExpect(jsonPath("$.claimed").value(true))
                .andExpect(jsonPath("$.accountId").value(ACCOUNT_ID.toString()))
                .andExpect(jsonPath("$.teamId").value(TEAM_ID.toString()))
                .andExpect(jsonPath("$.memberId").value(MEMBER_ID.toString()))
                .andDo(MockMvcRestDocumentationWrapper.document(
                        "getCurrentAccountMembershipClaimed",
                        CURRENT_MEMBERSHIP_DESCRIPTION,
                        CURRENT_MEMBERSHIP_SUMMARY,
                        queryParameters(
                                parameterWithName("teamId")
                                        .description("연결 상태를 확인할 팀 UUID")
                        ),
                        membershipReadHeaders(),
                        noStoreResponseHeaders(),
                        responseFields(
                                fieldWithPath("claimed")
                                        .description("항상 true인 연결 상태 표시"),
                                fieldWithPath("accountId")
                                        .description("현재 인증된 BATON 계정 UUID"),
                                fieldWithPath("teamId")
                                        .description("membership 팀 UUID"),
                                fieldWithPath("memberId")
                                        .description("계정에 영구 연결된 기존 구성원 UUID"),
                                fieldWithPath("claimedAt")
                                        .description("membership을 만든 UTC 시각")
                        )));
    }

    @DisplayName("계정 membership claim API는 기존 구성원을 canonical 계정에 연결한다")
    @Test
    void documentsAccountMembershipClaim() throws Exception {
        when(roundAuthorizationUseCase.claimMembership(any()))
                .thenReturn(new MembershipResult(ACCOUNT_ID, TEAM_ID, MEMBER_ID, NOW));

        mockMvc.perform(authenticatedMutation(
                        post(RoundAdministrationController.MEMBERSHIP_CLAIMS_PATH)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "teamId": "11111111-1111-4111-8111-111111111111",
                                          "seasonId": "22222222-2222-4222-8222-222222222222",
                                          "memberId": "33333333-3333-4333-8333-333333333333"
                                        }
                                        """),
                        true
                ))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(RequestIdFilter.HEADER_NAME, REQUEST_ID.toString()))
                .andExpect(jsonPath("$.accountId").value(ACCOUNT_ID.toString()))
                .andExpect(jsonPath("$.teamId").value(TEAM_ID.toString()))
                .andExpect(jsonPath("$.memberId").value(MEMBER_ID.toString()))
                .andDo(MockMvcRestDocumentationWrapper.document(
                        "claimAccountMembership",
                        "인증된 BATON 계정이 workspace 접근 키로 기존 활성 구성원 하나를 명시적으로 claim한다.",
                        "계정 구성원 membership claim",
                        administrationMutationHeaders(),
                        requestFields(
                                requestField(
                                        RoundAdministrationRequests.MembershipClaimRequest.class,
                                        "teamId",
                                        "claim할 구성원의 팀 UUID"
                                ),
                                requestField(
                                        RoundAdministrationRequests.MembershipClaimRequest.class,
                                        "seasonId",
                                        "claim할 구성원의 시즌 UUID"
                                ),
                                requestField(
                                        RoundAdministrationRequests.MembershipClaimRequest.class,
                                        "memberId",
                                        "계정에 연결할 기존 구성원 UUID"
                                )
                        ),
                        noStoreResponseHeaders(),
                        responseFields(
                                fieldWithPath("accountId").description("연결된 BATON 계정 UUID"),
                                fieldWithPath("teamId").description("membership 팀 UUID"),
                                fieldWithPath("memberId").description("연결된 기존 구성원 UUID"),
                                fieldWithPath("claimedAt").description("membership을 만든 UTC 시각")
                        )));
    }

    @DisplayName("ROUND room mapping 생성 API는 resource와 새 canonical room을 연결한다")
    @Test
    void documentsRoundRoomMappingCreation() throws Exception {
        when(roundAuthorizationUseCase.createRoomMapping(any()))
                .thenReturn(new RoomMappingResult(
                        ROOM_ID,
                        TEAM_ID,
                        SEASON_ID,
                        RESOURCE_ID,
                        NOW,
                        null
                ));

        mockMvc.perform(authenticatedMutation(
                        post(RoundAdministrationController.ROOM_MAPPINGS_PATH)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "teamId": "11111111-1111-4111-8111-111111111111",
                                          "seasonId": "22222222-2222-4222-8222-222222222222",
                                          "resourceId": "44444444-4444-4444-8444-444444444444"
                                        }
                                        """),
                        true
                ))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(RequestIdFilter.HEADER_NAME, REQUEST_ID.toString()))
                .andExpect(jsonPath("$.roomId").value(ROOM_ID))
                .andExpect(jsonPath("$.endedAt").value(nullValue()))
                .andDo(MockMvcRestDocumentationWrapper.document(
                        "createRoundRoomMapping",
                        "인증된 membership과 workspace 접근 키를 확인하고 역할 자료를 새 canonical ROUND room에 연결한다.",
                        "ROUND room mapping 생성",
                        administrationMutationHeaders(),
                        requestFields(
                                requestField(
                                        RoundAdministrationRequests.CreateRoomMappingRequest.class,
                                        "teamId",
                                        "mapping 팀 UUID"
                                ),
                                requestField(
                                        RoundAdministrationRequests.CreateRoomMappingRequest.class,
                                        "seasonId",
                                        "mapping 시즌 UUID"
                                ),
                                requestField(
                                        RoundAdministrationRequests.CreateRoomMappingRequest.class,
                                        "resourceId",
                                        "ROUND room에 연결할 역할 자료 UUID"
                                )
                        ),
                        noStoreResponseHeaders(),
                        responseFields(roomMappingResponseFields(true))));
    }

    @DisplayName("ROUND room mapping 종료 API는 room tombstone을 남기고 재사용을 막는다")
    @Test
    void documentsRoundRoomMappingEnd() throws Exception {
        Instant endedAt = NOW.plusSeconds(30);
        when(roundAuthorizationUseCase.endRoomMapping(any()))
                .thenReturn(new RoomMappingResult(
                        ROOM_ID,
                        TEAM_ID,
                        SEASON_ID,
                        RESOURCE_ID,
                        NOW,
                        endedAt
                ));

        mockMvc.perform(authenticatedMutation(
                        delete(RoundAdministrationController.ROOM_MAPPING_PATH_PATTERN, ROOM_ID),
                        true
                ))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(RequestIdFilter.HEADER_NAME, REQUEST_ID.toString()))
                .andExpect(jsonPath("$.roomId").value(ROOM_ID))
                .andExpect(jsonPath("$.endedAt").value("2026-08-08T12:35:26Z"))
                .andDo(MockMvcRestDocumentationWrapper.document(
                        "endRoundRoomMapping",
                        "active ROUND room mapping을 종료하고 room ID tombstone을 영구 보존해 재사용을 막는다.",
                        "ROUND room mapping 종료",
                        pathParameters(
                                parameterWithName("roomId")
                                        .description("종료할 canonical ROUND room ID")
                        ),
                        administrationMutationHeaders(),
                        noStoreResponseHeaders(),
                        responseFields(roomMappingResponseFields(false))));
    }

    @DisplayName("ROUND 참여권 갱신 API는 JWT를 body에 노출하지 않고 room cookie만 회전한다")
    @Test
    void documentsRoundParticipationGrantRefresh() throws Exception {
        long expiresAt = NOW.plusSeconds(300).getEpochSecond();
        when(roundAuthorizationUseCase.issueParticipationGrant(any()))
                .thenReturn(new ParticipationGrantResult(
                        "header.payload.signature",
                        expiresAt,
                        240,
                        ROOM_ID
                ));

        mockMvc.perform(authenticatedMutation(
                        post(ParticipationGrantController.REFRESH_PATH_PATTERN, ROOM_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "teamId": "11111111-1111-4111-8111-111111111111",
                                          "seasonId": "22222222-2222-4222-8222-222222222222",
                                          "resourceId": "44444444-4444-4444-8444-444444444444"
                                        }
                                        """),
                        false
                ))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(RequestIdFilter.HEADER_NAME, REQUEST_ID.toString()))
                .andExpect(header().string(
                        HttpHeaders.SET_COOKIE,
                        containsString("__Secure-round_access=header.payload.signature")
                ))
                .andExpect(jsonPath("$.expiresAt").value(expiresAt))
                .andExpect(jsonPath("$.refreshAfterSeconds").value(240))
                .andExpect(jsonPath("$.token").doesNotExist())
                .andDo(MockMvcRestDocumentationWrapper.document(
                        "refreshRoundParticipationGrant",
                        "현재 Account membership과 authoritative room mapping을 확인하고 room-scoped 참여권 cookie를 회전한다. locator hint body는 선택 사항이다.",
                        "ROUND 참여권 갱신",
                        pathParameters(
                                parameterWithName("roomId")
                                        .description("참여할 canonical ROUND room ID")
                        ),
                        sessionMutationHeaders(),
                        requestFields(
                                requestField(
                                        ParticipationGrantHintRequest.class,
                                        "teamId",
                                        "authoritative mapping과 대조할 팀 UUID"
                                ),
                                requestField(
                                        ParticipationGrantHintRequest.class,
                                        "seasonId",
                                        "authoritative mapping과 대조할 시즌 UUID"
                                ),
                                requestField(
                                        ParticipationGrantHintRequest.class,
                                        "resourceId",
                                        "authoritative mapping과 대조할 역할 자료 UUID"
                                )
                        ),
                        responseHeaders(
                                headerWithName(RequestIdFilter.HEADER_NAME)
                                        .description("서버가 생성한 불투명 요청 진단 식별자"),
                                headerWithName(HttpHeaders.CACHE_CONTROL)
                                        .description("민감 응답 캐시 금지"),
                                headerWithName(HttpHeaders.SET_COOKIE)
                                        .description("room path에 한정한 HttpOnly participation grant cookie")
                        ),
                        responseFields(
                                fieldWithPath("expiresAt")
                                        .description("발급한 참여권의 Unix epoch 만료 초"),
                                fieldWithPath("refreshAfterSeconds")
                                        .description("수신 시점부터 다음 갱신까지의 상대 초")
                        )));
    }

    @DisplayName("ROUND 공개 JWK Set API는 공개 RSA 검증 키만 짧게 캐시한다")
    @Test
    void documentsRoundParticipationJwkSet() throws Exception {
        when(readJwkSetUseCase.readPublicJwkSetJson()).thenReturn("""
                {
                  "keys": [
                    {
                      "kty": "RSA",
                      "kid": "baton-round-current",
                      "use": "sig",
                      "alg": "RS256",
                      "n": "base64url-modulus",
                      "e": "AQAB"
                    }
                  ]
                }
                """);

        mockMvc.perform(get(ParticipationGrantController.JWK_SET_PATH))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/jwk-set+json"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "max-age=60, public"))
                .andExpect(header().doesNotExist(RequestIdFilter.HEADER_NAME))
                .andDo(MockMvcRestDocumentationWrapper.document(
                        "getRoundParticipationJwkSet",
                        "ROUND가 BATON participation grant 서명을 검증할 현재·이전 공개 RSA 키만 JWK Set으로 조회한다.",
                        "ROUND participation JWK Set 조회",
                        responseHeaders(
                                headerWithName(HttpHeaders.CONTENT_TYPE)
                                        .description("표준 JWK Set media type"),
                                headerWithName(HttpHeaders.CACHE_CONTROL)
                                        .description("60초 public cache 지시자")
                        ),
                        responseFields(
                                fieldWithPath("keys")
                                        .type(JsonFieldType.ARRAY)
                                        .attributes(key("itemsType").value("OBJECT"))
                                        .description("현재·이전 공개 RSA 검증 키"),
                                fieldWithPath("keys[].kty").description("RSA key type"),
                                fieldWithPath("keys[].kid").description("서명 key 식별자"),
                                fieldWithPath("keys[].use").description("고정 용도 sig"),
                                fieldWithPath("keys[].alg").description("고정 알고리즘 RS256"),
                                fieldWithPath("keys[].n").description("base64url RSA modulus"),
                                fieldWithPath("keys[].e").description("base64url RSA public exponent")
                        )));
    }

    private MockHttpServletRequestBuilder authenticatedMutation(
            MockHttpServletRequestBuilder request,
            boolean includeAccessKey
    ) {
        MockHttpServletRequestBuilder authenticated = request
                .principal(authentication())
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .header("Sec-Fetch-Site", "same-origin")
                .header(CSRF_HEADER, CSRF_TOKEN);
        return includeAccessKey
                ? authenticated.header("X-Baton-Access-Key", ACCESS_KEY)
                : authenticated;
    }

    private UsernamePasswordAuthenticationToken authentication() {
        TestAccountPrincipal principal = new TestAccountPrincipal(ACCOUNT_ID);
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of());
    }

    private Snippet administrationMutationHeaders() {
        return requestHeaders(
                headerWithName("X-Baton-Access-Key")
                        .description("claim 또는 mapping 대상 workspace 접근 키"),
                headerWithName(HttpHeaders.ORIGIN)
                        .description("BATON 공개 origin과 정확히 같은 browser origin"),
                headerWithName("Sec-Fetch-Site")
                        .description("브라우저가 보낸 same-origin Fetch Metadata"),
                headerWithName(CSRF_HEADER)
                        .description("GET /api/v1/auth/csrf에서 받은 동적 CSRF token")
        );
    }

    private Snippet membershipReadHeaders() {
        return requestHeaders(
                headerWithName("X-Baton-Access-Key")
                        .description("연결 상태를 확인할 팀의 workspace 접근 키")
        );
    }

    private Snippet sessionMutationHeaders() {
        return requestHeaders(
                headerWithName(HttpHeaders.ORIGIN)
                        .description("BATON 공개 origin과 정확히 같은 browser origin"),
                headerWithName("Sec-Fetch-Site")
                        .description("브라우저가 보낸 same-origin Fetch Metadata"),
                headerWithName(CSRF_HEADER)
                        .description("GET /api/v1/auth/csrf에서 받은 동적 CSRF token")
        );
    }

    private Snippet noStoreResponseHeaders() {
        return responseHeaders(
                headerWithName(RequestIdFilter.HEADER_NAME)
                        .description("서버가 생성한 불투명 요청 진단 식별자"),
                headerWithName(HttpHeaders.CACHE_CONTROL)
                        .description("민감 응답 캐시 금지")
        );
    }

    private FieldDescriptor requestField(
            Class<?> requestType,
            String path,
            String description
    ) {
        return new ConstrainedFields(requestType).withPath(path).description(description);
    }

    private FieldDescriptor[] roomMappingResponseFields(boolean activeMapping) {
        FieldDescriptor endedAt = activeMapping
                ? fieldWithPath("endedAt")
                        .type(JsonFieldType.STRING)
                        .optional()
                        .description("종료 전에는 null인 mapping 종료 UTC 시각")
                : fieldWithPath("endedAt").description("mapping 종료 UTC 시각");
        return new FieldDescriptor[]{
                fieldWithPath("roomId").description("canonical ROUND room ID"),
                fieldWithPath("teamId").description("mapping 팀 UUID"),
                fieldWithPath("seasonId").description("mapping 시즌 UUID"),
                fieldWithPath("resourceId").description("mapping 역할 자료 UUID"),
                fieldWithPath("createdAt").description("mapping 생성 UTC 시각"),
                endedAt
        };
    }

    private record TestAccountPrincipal(UUID accountId)
            implements AuthenticatedAccountPrincipal {
    }

    private record ParticipationGrantHintRequest(
            @NotNull UUID teamId,
            @NotNull UUID seasonId,
            @NotNull UUID resourceId
    ) {
    }
}
