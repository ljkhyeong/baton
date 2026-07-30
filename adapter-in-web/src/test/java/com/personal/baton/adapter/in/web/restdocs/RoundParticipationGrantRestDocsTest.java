package com.personal.baton.adapter.in.web.restdocs;

import com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper;
import com.personal.baton.adapter.in.web.RequestIdFilter;
import com.personal.baton.adapter.in.web.config.SecurityConfig;
import com.personal.baton.adapter.in.web.config.WebFilterConfig;
import com.personal.baton.adapter.in.web.identity.BatonAccountPrincipal;
import com.personal.baton.adapter.in.web.round.RoundJwkSetController;
import com.personal.baton.adapter.in.web.round.RoundParticipationGrantController;
import com.personal.baton.application.identity.port.in.MemberIdentityUseCase.AuthenticatedAccount;
import com.personal.baton.application.round.error.RoundGrantOperationException;
import com.personal.baton.application.round.port.in.RoundParticipationGrantUseCase;
import com.personal.baton.application.round.port.in.RoundParticipationGrantUseCase.IssuedRoundParticipationGrant;
import com.personal.baton.application.round.port.in.RoundParticipationGrantUseCase.PublicRoundJwkSet;
import java.time.Clock;
import java.time.Instant;
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
import org.springframework.restdocs.RestDocumentationContextProvider;
import org.springframework.restdocs.RestDocumentationExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.context.WebApplicationContext;

import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.restdocs.headers.HeaderDocumentation.headerWithName;
import static org.springframework.restdocs.headers.HeaderDocumentation.requestHeaders;
import static org.springframework.restdocs.headers.HeaderDocumentation.responseHeaders;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.documentationConfiguration;
import static org.springframework.restdocs.operation.preprocess.Preprocessors.prettyPrint;
import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;
import static org.springframework.restdocs.payload.PayloadDocumentation.responseFields;
import static org.springframework.restdocs.request.RequestDocumentation.parameterWithName;
import static org.springframework.restdocs.request.RequestDocumentation.pathParameters;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.get;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@Tag("restdocs")
@ExtendWith(RestDocumentationExtension.class)
@WebMvcTest(controllers = {
        RoundParticipationGrantController.class,
        RoundJwkSetController.class
})
@Import({SecurityConfig.class, WebFilterConfig.class})
class RoundParticipationGrantRestDocsTest {

    private static final String GRANT_SUMMARY = "ROUND participant 참여권 발급";
    private static final String GRANT_DESCRIPTION =
            "로그인 계정의 현재 활성 팀 구성원 결속과 저장된 ROUND 역할 자료를 다시 확인하고 "
                    + "room 범위의 HttpOnly 참여권 쿠키를 발급한다.";
    private static final String ROOM_GRANT_SUMMARY = "복사한 ROUND room 참여권 발급";
    private static final String ROOM_GRANT_DESCRIPTION =
            "복사한 canonical room 경로를 현재 로그인 계정이 접근 가능한 정확히 하나의 "
                    + "활성 구성원 역할 자료로 해석하고 participant 쿠키를 발급한다.";
    private static final String JWKS_SUMMARY = "ROUND 참여권 공개키 조회";
    private static final String JWKS_DESCRIPTION =
            "ROUND가 RS256 참여권을 검증할 수 있도록 rotation 중인 RSA 공개키 집합만 반환한다.";
    private static final UUID REQUEST_ID =
            UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final UUID ACCOUNT_ID =
            UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID TEAM_ID =
            UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID SEASON_ID =
            UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID RESOURCE_ID =
            UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final Instant ISSUED_AT = Instant.parse("2026-07-31T03:00:00Z");

    @Autowired
    private WebApplicationContext applicationContext;

    @MockitoBean
    private Clock clock;

    @MockitoBean
    private RoundParticipationGrantUseCase useCase;

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

    @DisplayName("ROUND 참여권 API는 본문 없이 room 범위 보안 쿠키를 발급한다")
    @Test
    void documentsRoundParticipationGrant() throws Exception {
        when(useCase.issue(TEAM_ID, SEASON_ID, RESOURCE_ID, account()))
                .thenReturn(grant());

        performGrant()
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().string(""))
                .andDo(MockMvcRestDocumentationWrapper.document(
                        "issueRoundParticipationGrant",
                        GRANT_DESCRIPTION,
                        GRANT_SUMMARY,
                        grantPathParameters(),
                        grantRequestHeaders(),
                        responseHeaders(
                                headerWithName(RequestIdFilter.HEADER_NAME)
                                        .description("서버가 생성한 불투명 요청 진단 식별자"),
                                headerWithName(HttpHeaders.CACHE_CONTROL)
                                        .description("참여권 응답을 저장하지 않는 no-store 지시자"),
                                headerWithName(HttpHeaders.SET_COOKIE)
                                        .description(
                                                "Secure·HttpOnly·SameSite=Strict이고 "
                                                        + "요청 room path로 제한한 참여권 쿠키"
                                        )
                        )
                ));
    }

    @DisplayName("ROUND 참여권 API는 권한과 자료 및 signer 오류를 안정적으로 구분한다")
    @Test
    void documentsRoundParticipationGrantErrors() throws Exception {
        reset(useCase);
        when(useCase.issue(TEAM_ID, SEASON_ID, RESOURCE_ID, account()))
                .thenThrow(new RoundGrantOperationException(
                        "ROUND_GRANT_FORBIDDEN",
                        "현재 팀의 활성 구성원만 ROUND에 참여할 수 있습니다"
                ))
                .thenThrow(new RoundGrantOperationException(
                        "ROUND_RESOURCE_NOT_FOUND",
                        "요청한 ROUND 역할 자료를 찾을 수 없습니다"
                ))
                .thenThrow(new RoundGrantOperationException(
                        "ROUND_RESOURCE_NOT_ELIGIBLE",
                        "역할 자료가 canonical ROUND room을 가리키지 않습니다"
                ))
                .thenThrow(new RoundGrantOperationException(
                        "ROUND_GRANT_SIGNER_UNAVAILABLE",
                        "ROUND 참여권 서명기를 사용할 수 없습니다"
                ));

        performGrant()
                .andExpect(status().isForbidden())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andDo(documentGrantError(
                        "issueRoundParticipationGrantForbidden"
                ));

        performGrant()
                .andExpect(status().isNotFound())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andDo(documentGrantError(
                        "issueRoundParticipationGrantNotFound"
                ));

        performGrant()
                .andExpect(status().isConflict())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andDo(documentGrantError(
                        "issueRoundParticipationGrantNotEligible"
                ));

        performGrant()
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value(
                        "ROUND_GRANT_SIGNER_UNAVAILABLE"
                ))
                .andDo(documentGrantError(
                        "issueRoundParticipationGrantSignerUnavailable"
                ));
    }

    @DisplayName("복사한 ROUND room 참여권 API도 본문 없이 같은 보안 쿠키를 발급한다")
    @Test
    void documentsRoundRoomParticipationGrant() throws Exception {
        when(useCase.issueForRoom("abcd-efgh-jkmn", account()))
                .thenReturn(grant());

        performRoomGrant()
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().string(""))
                .andDo(MockMvcRestDocumentationWrapper.document(
                        "issueRoundRoomParticipationGrant",
                        ROOM_GRANT_DESCRIPTION,
                        ROOM_GRANT_SUMMARY,
                        pathParameters(
                                parameterWithName("roomId")
                                        .description("복사한 canonical ROUND room 식별자")
                        ),
                        grantRequestHeaders(),
                        responseHeaders(
                                headerWithName(RequestIdFilter.HEADER_NAME)
                                        .description("서버가 생성한 불투명 요청 진단 식별자"),
                                headerWithName(HttpHeaders.CACHE_CONTROL)
                                        .description("참여권 응답을 저장하지 않는 no-store 지시자"),
                                headerWithName(HttpHeaders.SET_COOKIE)
                                        .description(
                                                "Secure·HttpOnly·SameSite=Strict이고 "
                                                        + "요청 room path로 제한한 참여권 쿠키"
                                        )
                        )
                ));
    }

    @DisplayName("복사한 ROUND room 참여권 API는 권한 없음과 모호한 자료를 구분한다")
    @Test
    void documentsRoundRoomParticipationGrantErrors() throws Exception {
        reset(useCase);
        when(useCase.issueForRoom("abcd-efgh-jkmn", account()))
                .thenThrow(new RoundGrantOperationException(
                        "ROUND_GRANT_FORBIDDEN",
                        "현재 계정으로 이 ROUND room에 참여할 수 없습니다"
                ))
                .thenThrow(new RoundGrantOperationException(
                        "ROUND_RESOURCE_AMBIGUOUS",
                        "ROUND room에 연결된 역할 자료를 하나로 결정할 수 없습니다"
                ))
                .thenThrow(new RoundGrantOperationException(
                        "ROUND_GRANT_SIGNER_UNAVAILABLE",
                        "ROUND 참여권 서명기를 사용할 수 없습니다"
                ));

        performRoomGrant()
                .andExpect(status().isForbidden())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andDo(documentRoomGrantError(
                        "issueRoundRoomParticipationGrantForbidden"
                ));

        performRoomGrant()
                .andExpect(status().isConflict())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andDo(documentRoomGrantError(
                        "issueRoundRoomParticipationGrantAmbiguous"
                ));

        performRoomGrant()
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andDo(documentRoomGrantError(
                        "issueRoundRoomParticipationGrantSignerUnavailable"
                ));
    }

    @DisplayName("JWKS API는 공개 RSA key ring과 ETag를 반환한다")
    @Test
    void documentsRoundPublicJwkSet() throws Exception {
        when(useCase.getPublicJwkSet()).thenReturn(jwkSet());

        mockMvc.perform(get("/.well-known/jwks.json"))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        HttpHeaders.ETAG,
                        "\"sha256-public-key-set\""
                ))
                .andDo(MockMvcRestDocumentationWrapper.document(
                        "getRoundJwkSet",
                        JWKS_DESCRIPTION,
                        JWKS_SUMMARY,
                        responseHeaders(
                                headerWithName(RequestIdFilter.HEADER_NAME)
                                        .description("서버가 생성한 불투명 요청 진단 식별자"),
                                headerWithName(HttpHeaders.CACHE_CONTROL)
                                        .description(
                                                "public, max-age=60, must-revalidate 캐시 지시자"
                                        ),
                                headerWithName(HttpHeaders.ETAG)
                                        .description("공개키 집합 내용의 강한 ETag")
                        ),
                        responseFields(
                                fieldWithPath("keys").description("검증 가능한 RSA 공개키 목록"),
                                fieldWithPath("keys[].kid").description("서명 key 식별자"),
                                fieldWithPath("keys[].kty").description("RSA key 유형"),
                                fieldWithPath("keys[].alg").description("RS256 서명 알고리즘"),
                                fieldWithPath("keys[].use").description("서명 검증 용도"),
                                fieldWithPath("keys[].n").description("RSA 공개 modulus"),
                                fieldWithPath("keys[].e").description("RSA 공개 exponent")
                        )
                ));
    }

    @DisplayName("JWKS API는 같은 ETag에 본문 없는 304를 반환한다")
    @Test
    void documentsRoundPublicJwkSetNotModified() throws Exception {
        when(useCase.getPublicJwkSet()).thenReturn(jwkSet());

        mockMvc.perform(get("/.well-known/jwks.json")
                        .header(HttpHeaders.IF_NONE_MATCH, "\"sha256-public-key-set\""))
                .andExpect(status().isNotModified())
                .andExpect(content().string(""))
                .andDo(MockMvcRestDocumentationWrapper.document(
                        "getRoundJwkSetNotModified",
                        JWKS_DESCRIPTION,
                        JWKS_SUMMARY,
                        requestHeaders(
                                headerWithName(HttpHeaders.IF_NONE_MATCH)
                                        .description("이전에 받은 공개키 집합 ETag")
                                        .optional()
                        ),
                        responseHeaders(
                                headerWithName(RequestIdFilter.HEADER_NAME)
                                        .description("서버가 생성한 불투명 요청 진단 식별자"),
                                headerWithName(HttpHeaders.CACHE_CONTROL)
                                        .description(
                                                "public, max-age=60, must-revalidate 캐시 지시자"
                                        ),
                                headerWithName(HttpHeaders.ETAG)
                                        .description("현재 공개키 집합 ETag")
                        )
                ));
    }

    private ResultActions performGrant() throws Exception {
        return mockMvc.perform(post(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}"
                                + "/role-resources/{resourceId}"
                                + "/round-participation-grant",
                        TEAM_ID,
                        SEASON_ID,
                        RESOURCE_ID
                )
                .header(HttpHeaders.ORIGIN, "http://localhost:8080")
                .header("Sec-Fetch-Site", "same-origin")
                .with(authentication(accountAuthentication()))
                .with(csrf().asHeader()));
    }

    private ResultActions performRoomGrant() throws Exception {
        return mockMvc.perform(post(
                        "/api/v1/round/rooms/{roomId}/participation-grant",
                        "abcd-efgh-jkmn"
                )
                .header(HttpHeaders.ORIGIN, "http://localhost:8080")
                .header("Sec-Fetch-Site", "same-origin")
                .with(authentication(accountAuthentication()))
                .with(csrf().asHeader()));
    }

    private org.springframework.restdocs.mockmvc.RestDocumentationResultHandler
            documentGrantError(String identifier) {
        return MockMvcRestDocumentationWrapper.document(
                identifier,
                GRANT_DESCRIPTION,
                GRANT_SUMMARY,
                grantPathParameters(),
                grantRequestHeaders(),
                responseHeaders(
                        headerWithName(RequestIdFilter.HEADER_NAME)
                                .description("서버가 생성한 불투명 요청 진단 식별자"),
                        headerWithName(HttpHeaders.CACHE_CONTROL)
                                .description("오류 응답을 저장하지 않는 no-store 지시자")
                ),
                responseFields(
                        fieldWithPath("code").description("안정적인 오류 코드"),
                        fieldWithPath("message").description("사용자용 오류 설명")
                )
        );
    }

    private org.springframework.restdocs.mockmvc.RestDocumentationResultHandler
            documentRoomGrantError(String identifier) {
        return MockMvcRestDocumentationWrapper.document(
                identifier,
                ROOM_GRANT_DESCRIPTION,
                ROOM_GRANT_SUMMARY,
                pathParameters(
                        parameterWithName("roomId")
                                .description("복사한 canonical ROUND room 식별자")
                ),
                grantRequestHeaders(),
                responseHeaders(
                        headerWithName(RequestIdFilter.HEADER_NAME)
                                .description("서버가 생성한 불투명 요청 진단 식별자"),
                        headerWithName(HttpHeaders.CACHE_CONTROL)
                                .description("오류 응답을 저장하지 않는 no-store 지시자")
                ),
                responseFields(
                        fieldWithPath("code").description("안정적인 오류 코드"),
                        fieldWithPath("message").description("사용자용 오류 설명")
                )
        );
    }

    private org.springframework.restdocs.snippet.Snippet grantPathParameters() {
        return pathParameters(
                parameterWithName("teamId").description("팀 UUID"),
                parameterWithName("seasonId").description("회차 UUID"),
                parameterWithName("resourceId").description("저장된 역할 자료 UUID")
        );
    }

    private org.springframework.restdocs.snippet.Snippet grantRequestHeaders() {
        return requestHeaders(
                headerWithName(HttpHeaders.ORIGIN)
                        .description("설정된 BATON origin과 정확히 같은 요청 origin"),
                headerWithName("Sec-Fetch-Site")
                        .description("반드시 same-origin인 Fetch Metadata"),
                headerWithName("X-CSRF-TOKEN")
                        .description("현재 인증 세션에 결속된 CSRF 토큰")
        );
    }

    private IssuedRoundParticipationGrant grant() {
        return new IssuedRoundParticipationGrant(
                "header.payload.signature",
                "abcd-efgh-jkmn",
                ISSUED_AT,
                ISSUED_AT.plusSeconds(300),
                300
        );
    }

    private PublicRoundJwkSet jwkSet() {
        return new PublicRoundJwkSet(
                """
                        {
                          "keys": [
                            {
                              "kid": "round-2026-07",
                              "kty": "RSA",
                              "alg": "RS256",
                              "use": "sig",
                              "n": "public-modulus",
                              "e": "AQAB"
                            }
                          ]
                        }
                        """,
                "sha256-public-key-set"
        );
    }

    private AuthenticatedAccount account() {
        return new AuthenticatedAccount(ACCOUNT_ID);
    }

    private org.springframework.security.core.Authentication accountAuthentication() {
        return UsernamePasswordAuthenticationToken.authenticated(
                new BatonAccountPrincipal(ACCOUNT_ID),
                null,
                List.of()
        );
    }
}
