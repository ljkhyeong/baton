package com.personal.baton.adapter.in.web.roundauth;

import com.personal.baton.adapter.in.web.auth.AuthenticatedAccountPrincipal;
import com.personal.baton.application.roundauth.port.in.RoundAuthorizationUseCase;
import com.personal.baton.application.roundauth.port.in.RoundAuthorizationUseCase.ClaimMembershipCommand;
import com.personal.baton.application.roundauth.port.in.RoundAuthorizationUseCase.CreateRoomMappingCommand;
import com.personal.baton.application.roundauth.port.in.RoundAuthorizationUseCase.CurrentMembershipQuery;
import com.personal.baton.application.roundauth.port.in.RoundAuthorizationUseCase.CurrentRoomMappingsQuery;
import com.personal.baton.application.roundauth.port.in.RoundAuthorizationUseCase.EndRoomMappingCommand;
import com.personal.baton.application.roundauth.port.in.RoundAuthorizationUseCase.MembershipResult;
import com.personal.baton.application.roundauth.port.in.RoundAuthorizationUseCase.RoomMappingResult;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.web.DefaultSecurityFilterChain;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RoundAdministrationControllerTest {

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
    private static final Instant CREATED_AT = Instant.parse("2026-08-08T12:34:56Z");

    private RoundAuthorizationUseCase useCase;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        useCase = mock(RoundAuthorizationUseCase.class);
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new RoundAdministrationController(useCase)
                )
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .apply(springSecurity(new FilterChainProxy(new DefaultSecurityFilterChain(
                        AnyRequestMatcher.INSTANCE,
                        new SecurityContextHolderFilter(
                                new HttpSessionSecurityContextRepository()
                        )
                ))))
                .build();
    }

    @Test
    @DisplayName("현재 연결 조회는 claim된 멤버십을 exact web 합 타입으로 반환한다")
    void mapsClaimedCurrentMembershipToExactWebResponse() throws Exception {
        when(useCase.findCurrentMembership(new CurrentMembershipQuery(
                ACCOUNT_ID,
                TEAM_ID,
                ACCESS_KEY
        ))).thenReturn(Optional.of(
                new MembershipResult(ACCOUNT_ID, TEAM_ID, MEMBER_ID, CREATED_AT)
        ));

        mockMvc.perform(get(RoundAdministrationController.CURRENT_MEMBERSHIP_PATH)
                        .with(authentication(accountAuthentication()))
                        .queryParam("teamId", TEAM_ID.toString())
                        .header("X-Baton-Access-Key", ACCESS_KEY))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().json("""
                        {
                          "claimed": true,
                          "accountId": "8e448211-66ae-44ab-9888-c4960648c22b",
                          "teamId": "11111111-1111-4111-8111-111111111111",
                          "memberId": "33333333-3333-4333-8333-333333333333",
                          "claimedAt": "2026-08-08T12:34:56Z"
                        }
                        """, true));

        verify(useCase).findCurrentMembership(new CurrentMembershipQuery(
                ACCOUNT_ID,
                TEAM_ID,
                ACCESS_KEY
        ));
    }

    @Test
    @DisplayName("현재 연결 조회는 미연결 상태를 claimed false 한 필드만으로 반환한다")
    void mapsUnclaimedCurrentMembershipToExactWebResponse() throws Exception {
        when(useCase.findCurrentMembership(new CurrentMembershipQuery(
                ACCOUNT_ID,
                TEAM_ID,
                ACCESS_KEY
        ))).thenReturn(Optional.empty());

        mockMvc.perform(get(RoundAdministrationController.CURRENT_MEMBERSHIP_PATH)
                        .with(authentication(accountAuthentication()))
                        .queryParam("teamId", TEAM_ID.toString())
                        .header("X-Baton-Access-Key", ACCESS_KEY))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().json("""
                        {
                          "claimed": false
                        }
                        """, true));
    }

    @Test
    @DisplayName("구성원 claim 응답은 application result를 전용 web DTO로 변환한다")
    void mapsMembershipClaimToWebResponse() throws Exception {
        when(useCase.claimMembership(new ClaimMembershipCommand(
                ACCOUNT_ID,
                TEAM_ID,
                SEASON_ID,
                MEMBER_ID,
                ACCESS_KEY
        ))).thenReturn(new MembershipResult(ACCOUNT_ID, TEAM_ID, MEMBER_ID, CREATED_AT));

        mockMvc.perform(post(RoundAdministrationController.MEMBERSHIP_CLAIMS_PATH)
                        .with(authentication(accountAuthentication()))
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "teamId": "11111111-1111-4111-8111-111111111111",
                                  "seasonId": "22222222-2222-4222-8222-222222222222",
                                  "memberId": "33333333-3333-4333-8333-333333333333"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().json("""
                        {
                          "accountId": "8e448211-66ae-44ab-9888-c4960648c22b",
                          "teamId": "11111111-1111-4111-8111-111111111111",
                          "memberId": "33333333-3333-4333-8333-333333333333",
                          "claimedAt": "2026-08-08T12:34:56Z"
                        }
                        """, true));

        verify(useCase).claimMembership(new ClaimMembershipCommand(
                ACCOUNT_ID,
                TEAM_ID,
                SEASON_ID,
                MEMBER_ID,
                ACCESS_KEY
        ));
    }

    @Test
    @DisplayName("ROUND room mapping 생성 응답은 nullable 종료 시각을 가진 전용 web DTO다")
    void mapsCreatedRoomMappingToWebResponse() throws Exception {
        when(useCase.createRoomMapping(new CreateRoomMappingCommand(
                ACCOUNT_ID,
                TEAM_ID,
                SEASON_ID,
                RESOURCE_ID,
                ACCESS_KEY
        ))).thenReturn(mappingResult(null));

        mockMvc.perform(post(RoundAdministrationController.ROOM_MAPPINGS_PATH)
                        .with(authentication(accountAuthentication()))
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "teamId": "11111111-1111-4111-8111-111111111111",
                                  "seasonId": "22222222-2222-4222-8222-222222222222",
                                  "resourceId": "44444444-4444-4444-8444-444444444444"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().json("""
                        {
                          "roomId": "bcdf-ghjk-mnpq",
                          "teamId": "11111111-1111-4111-8111-111111111111",
                          "seasonId": "22222222-2222-4222-8222-222222222222",
                          "resourceId": "44444444-4444-4444-8444-444444444444",
                          "createdAt": "2026-08-08T12:34:56Z",
                          "endedAt": null
                        }
                        """, true));

        verify(useCase).createRoomMapping(new CreateRoomMappingCommand(
                ACCOUNT_ID,
                TEAM_ID,
                SEASON_ID,
                RESOURCE_ID,
                ACCESS_KEY
        ));
    }

    @Test
    @DisplayName("현재 ROUND 방 목록 조회는 서버에 매핑이 없으면 빈 배열을 반환한다")
    void mapsMissingCurrentRoomMappingsToExactWebResponse() throws Exception {
        CurrentRoomMappingsQuery query = new CurrentRoomMappingsQuery(
                ACCOUNT_ID,
                TEAM_ID,
                SEASON_ID,
                ACCESS_KEY
        );
        when(useCase.findCurrentRoomMappings(query)).thenReturn(List.of());

        mockMvc.perform(get(RoundAdministrationController.ROOM_MAPPINGS_PATH)
                        .with(authentication(accountAuthentication()))
                        .queryParam("teamId", TEAM_ID.toString())
                        .queryParam("seasonId", SEASON_ID.toString())
                        .header("X-Baton-Access-Key", ACCESS_KEY))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().json("""
                        {
                          "mappings": []
                        }
                        """, true));

        verify(useCase).findCurrentRoomMappings(query);
    }

    @Test
    @DisplayName("현재 ROUND 방 목록 조회는 팀과 시즌의 active 매핑을 exact 배열 응답으로 반환한다")
    void mapsCurrentRoomMappingsToExactWebResponse() throws Exception {
        CurrentRoomMappingsQuery query = new CurrentRoomMappingsQuery(
                ACCOUNT_ID,
                TEAM_ID,
                SEASON_ID,
                ACCESS_KEY
        );
        when(useCase.findCurrentRoomMappings(query))
                .thenReturn(List.of(mappingResult(null)));

        mockMvc.perform(get(RoundAdministrationController.ROOM_MAPPINGS_PATH)
                        .with(authentication(accountAuthentication()))
                        .queryParam("teamId", TEAM_ID.toString())
                        .queryParam("seasonId", SEASON_ID.toString())
                        .header("X-Baton-Access-Key", ACCESS_KEY))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().json("""
                        {
                          "mappings": [
                            {
                              "roomId": "bcdf-ghjk-mnpq",
                              "teamId": "11111111-1111-4111-8111-111111111111",
                              "seasonId": "22222222-2222-4222-8222-222222222222",
                              "resourceId": "44444444-4444-4444-8444-444444444444",
                              "createdAt": "2026-08-08T12:34:56Z",
                              "endedAt": null
                            }
                          ]
                        }
                        """, true));

        verify(useCase).findCurrentRoomMappings(query);
    }

    @Test
    @DisplayName("ROUND room mapping 종료 응답은 path room ID와 종료 시각을 web 계약으로 변환한다")
    void mapsEndedRoomMappingToWebResponse() throws Exception {
        Instant endedAt = CREATED_AT.plusSeconds(600);
        when(useCase.endRoomMapping(new EndRoomMappingCommand(
                ACCOUNT_ID,
                ROOM_ID,
                ACCESS_KEY
        ))).thenReturn(mappingResult(endedAt));

        mockMvc.perform(delete(RoundAdministrationController.ROOM_MAPPING_PATH_PATTERN, ROOM_ID)
                        .with(authentication(accountAuthentication()))
                        .header("X-Baton-Access-Key", ACCESS_KEY))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().json("""
                        {
                          "roomId": "bcdf-ghjk-mnpq",
                          "teamId": "11111111-1111-4111-8111-111111111111",
                          "seasonId": "22222222-2222-4222-8222-222222222222",
                          "resourceId": "44444444-4444-4444-8444-444444444444",
                          "createdAt": "2026-08-08T12:34:56Z",
                          "endedAt": "2026-08-08T12:44:56Z"
                        }
                        """, true));

        verify(useCase).endRoomMapping(new EndRoomMappingCommand(
                ACCOUNT_ID,
                ROOM_ID,
                ACCESS_KEY
        ));
    }

    private RoomMappingResult mappingResult(Instant endedAt) {
        return new RoomMappingResult(
                ROOM_ID,
                TEAM_ID,
                SEASON_ID,
                RESOURCE_ID,
                CREATED_AT,
                endedAt
        );
    }

    private UsernamePasswordAuthenticationToken accountAuthentication() {
        AuthenticatedAccountPrincipal principal = () -> ACCOUNT_ID;
        return UsernamePasswordAuthenticationToken.authenticated(
                principal,
                null,
                List.of()
        );
    }
}
