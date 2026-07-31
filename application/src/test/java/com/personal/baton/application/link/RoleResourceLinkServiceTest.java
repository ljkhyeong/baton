package com.personal.baton.application.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;

import com.personal.baton.application.link.error.InvalidLinkIntentException;
import com.personal.baton.application.link.port.in.RoleResourceLinkUseCase.OpenRoleResourceLinkResult;
import com.personal.baton.application.link.port.in.RoleResourceLinkUseCase.RoutingMode;
import com.personal.baton.application.link.port.out.RoleResourceLinkPort;
import com.personal.baton.application.workspace.error.WorkspaceNotFoundException;
import com.personal.baton.application.workspace.port.in.WorkspaceAuthorization.LegacyAccessKey;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.RoleResourceResult;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RoleResourceLinkServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-30T12:00:00Z");
    private static final UUID TEAM_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID SEASON_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID RESOURCE_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID IDEMPOTENCY_KEY =
            UUID.fromString("a4444444-4444-4444-8444-444444444444");
    private static final String ACCESS_KEY = "browser-only-workspace-access-key";
    private static final URI RESOURCE_URL =
            URI.create("https://round.example/room/abcd-efgh-jkmn");

    @Mock
    private WorkspaceUseCase workspaceUseCase;

    @Mock
    private RoleResourceLinkPort roleResourceLinkPort;

    private RoleResourceLinkService service;

    @BeforeEach
    void setUp() {
        service = new RoleResourceLinkService(
                workspaceUseCase,
                roleResourceLinkPort,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    @DisplayName("권한을 확인한 뒤 일반 자료 URL을 직접 이동 결과로 반환한다")
    void returnsDirectNavigationAfterWorkspaceAuthorization() {
        Instant expiresAt = NOW.plusSeconds(300);
        given(workspaceUseCase.getRoleResourceForGrantAuthorized(
                TEAM_ID,
                SEASON_ID,
                RESOURCE_ID,
                authorization()
        )).willReturn(resource(RESOURCE_URL));
        given(roleResourceLinkPort.createNavigation(RESOURCE_URL, IDEMPOTENCY_KEY, expiresAt))
                .willReturn(new RoleResourceLinkPort.LinkNavigation(RESOURCE_URL, false, null));

        OpenRoleResourceLinkResult result = service.openRoleResourceLink(
                TEAM_ID,
                SEASON_ID,
                RESOURCE_ID,
                ACCESS_KEY,
                IDEMPOTENCY_KEY.toString(),
                expiresAt
        );

        assertThat(result.navigationUrl()).isEqualTo(RESOURCE_URL);
        assertThat(result.routingMode()).isEqualTo(RoutingMode.DIRECT);
        assertThat(result.expiresAt()).isNull();
        InOrder order = inOrder(workspaceUseCase, roleResourceLinkPort);
        order.verify(workspaceUseCase).getRoleResourceForGrantAuthorized(
                TEAM_ID,
                SEASON_ID,
                RESOURCE_ID,
                authorization()
        );
        order.verify(roleResourceLinkPort).createNavigation(
                RESOURCE_URL,
                IDEMPOTENCY_KEY,
                expiresAt
        );
    }

    @Test
    @DisplayName("신뢰된 회의 자료는 BATON GO 이동 결과와 만료 시각을 반환한다")
    void returnsBatonGoNavigation() {
        Instant expiresAt = NOW.plusSeconds(600);
        URI shortUrl = URI.create("https://go.example/l/opaque-code");
        given(workspaceUseCase.getRoleResourceForGrantAuthorized(
                TEAM_ID,
                SEASON_ID,
                RESOURCE_ID,
                authorization()
        )).willReturn(resource(RESOURCE_URL));
        given(roleResourceLinkPort.createNavigation(RESOURCE_URL, IDEMPOTENCY_KEY, expiresAt))
                .willReturn(new RoleResourceLinkPort.LinkNavigation(shortUrl, true, expiresAt));

        OpenRoleResourceLinkResult result = service.openRoleResourceLink(
                TEAM_ID,
                SEASON_ID,
                RESOURCE_ID,
                ACCESS_KEY,
                IDEMPOTENCY_KEY.toString(),
                expiresAt
        );

        assertThat(result.navigationUrl()).isEqualTo(shortUrl);
        assertThat(result.routingMode()).isEqualTo(RoutingMode.BATON_GO);
        assertThat(result.expiresAt()).isEqualTo(expiresAt);
    }

    @Test
    @DisplayName("워크스페이스 projection에 없는 자료는 안정적인 찾기 오류로 거부한다")
    void rejectsMissingResource() {
        given(workspaceUseCase.getRoleResourceForGrantAuthorized(
                TEAM_ID,
                SEASON_ID,
                RESOURCE_ID,
                authorization()
        )).willThrow(new WorkspaceNotFoundException(
                "ROLE_RESOURCE_NOT_FOUND",
                "자료를 찾을 수 없습니다"
        ));

        assertThatThrownBy(() -> service.openRoleResourceLink(
                TEAM_ID,
                SEASON_ID,
                RESOURCE_ID,
                ACCESS_KEY,
                IDEMPOTENCY_KEY.toString(),
                NOW.plusSeconds(300)
        ))
                .isInstanceOfSatisfying(
                        WorkspaceNotFoundException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo("ROLE_RESOURCE_NOT_FOUND")
                );
        verifyNoInteractions(roleResourceLinkPort);
    }

    @Test
    @DisplayName("canonical UUID가 아닌 링크 멱등 키는 원격 호출 전에 거부한다")
    void rejectsNonCanonicalIdempotencyKey() {
        given(workspaceUseCase.getRoleResourceForGrantAuthorized(
                TEAM_ID,
                SEASON_ID,
                RESOURCE_ID,
                authorization()
        )).willReturn(resource(RESOURCE_URL));

        assertThatThrownBy(() -> service.openRoleResourceLink(
                TEAM_ID,
                SEASON_ID,
                RESOURCE_ID,
                ACCESS_KEY,
                IDEMPOTENCY_KEY.toString().toUpperCase(),
                NOW.plusSeconds(300)
        ))
                .isInstanceOfSatisfying(
                        InvalidLinkIntentException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo("INVALID_LINK_IDEMPOTENCY_KEY")
                );
        verifyNoInteractions(roleResourceLinkPort);
    }

    @Test
    @DisplayName("현재와 같거나 15분을 넘는 링크 만료 시각은 거부한다")
    void rejectsInvalidExpiry() {
        given(workspaceUseCase.getRoleResourceForGrantAuthorized(
                TEAM_ID,
                SEASON_ID,
                RESOURCE_ID,
                authorization()
        )).willReturn(resource(RESOURCE_URL));

        assertInvalidExpiry(null);
        assertInvalidExpiry(NOW);
        assertInvalidExpiry(NOW.plusSeconds(900).plusNanos(1));
        verifyNoInteractions(roleResourceLinkPort);
    }

    @Test
    @DisplayName("현재부터 정확히 15분인 링크 만료 시각은 허용한다")
    void acceptsMaximumExpiryBoundary() {
        Instant expiresAt = NOW.plusSeconds(900);
        given(workspaceUseCase.getRoleResourceForGrantAuthorized(
                TEAM_ID,
                SEASON_ID,
                RESOURCE_ID,
                authorization()
        )).willReturn(resource(RESOURCE_URL));
        given(roleResourceLinkPort.createNavigation(RESOURCE_URL, IDEMPOTENCY_KEY, expiresAt))
                .willReturn(new RoleResourceLinkPort.LinkNavigation(RESOURCE_URL, false, null));

        OpenRoleResourceLinkResult result = service.openRoleResourceLink(
                TEAM_ID,
                SEASON_ID,
                RESOURCE_ID,
                ACCESS_KEY,
                IDEMPOTENCY_KEY.toString(),
                expiresAt
        );

        assertThat(result.routingMode()).isEqualTo(RoutingMode.DIRECT);
    }

    private void assertInvalidExpiry(Instant expiresAt) {
        assertThatThrownBy(() -> service.openRoleResourceLink(
                TEAM_ID,
                SEASON_ID,
                RESOURCE_ID,
                ACCESS_KEY,
                IDEMPOTENCY_KEY.toString(),
                expiresAt
        ))
                .isInstanceOfSatisfying(
                        InvalidLinkIntentException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo("INVALID_LINK_EXPIRY")
                );
    }

    private LegacyAccessKey authorization() {
        return new LegacyAccessKey(ACCESS_KEY);
    }

    private RoleResourceResult resource(URI resourceUrl) {
        return new RoleResourceResult(
                RESOURCE_ID,
                UUID.fromString("55555555-5555-4555-8555-555555555555"),
                "주간 회의",
                resourceUrl.toString(),
                "회의 입장 링크"
        );
    }
}
