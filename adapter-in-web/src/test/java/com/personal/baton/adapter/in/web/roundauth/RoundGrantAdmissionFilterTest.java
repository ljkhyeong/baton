package com.personal.baton.adapter.in.web.roundauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.personal.baton.adapter.in.web.auth.AuthenticatedAccountPrincipal;
import com.personal.baton.adapter.in.web.auth.AccountSessionPrincipal;
import com.personal.baton.adapter.in.web.auth.AuthRateLimiter;
import com.personal.baton.adapter.in.web.security.SecurityErrorResponseWriter;
import jakarta.servlet.FilterChain;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.ObjectMapper;

class RoundGrantAdmissionFilterTest {

    private static final String PATH =
            "/round/rooms/bcdf-ghjk-mnpq/participation-grant/refresh";
    private static final UUID ACCOUNT_ID =
            UUID.fromString("8e448211-66ae-44ab-9888-c4960648c22b");

    private final RoundGrantAdmissionFilter filter = new RoundGrantAdmissionFilter(
            new AuthRateLimiter(),
            new SecurityErrorResponseWriter(new ObjectMapper())
    );

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("교차 출처 갱신은 인증 여부를 노출하지 않고 쿠키를 유지한 채 거절한다")
    void rejectsCrossOriginBeforeAuthentication() throws Exception {
        MockHttpServletRequest request = request();
        request.addHeader(HttpHeaders.ORIGIN, "https://attacker.example");
        request.addHeader("Sec-Fetch-Site", "cross-site");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(response.getContentAsString()).isEqualTo(
                "{\"code\":\"REQUEST_FORBIDDEN\",\"message\":\"동일 출처 요청만 허용됩니다\"}"
        );
        assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).isNull();
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    @DisplayName("동일 출처지만 세션이 없으면 401과 해당 방 범위의 만료 쿠키를 반환한다")
    void clearsGrantCookieWithoutAccountSession() throws Exception {
        MockHttpServletRequest request = sameOriginRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(response.getContentAsString()).isEqualTo(
                "{\"code\":\"AUTHENTICATION_REQUIRED\",\"message\":\"BATON 계정 로그인이 필요합니다\"}"
        );
        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).isNull();
        assertThat(response.getHeader(HttpHeaders.SET_COOKIE))
                .contains("Max-Age=0", "Path=/round/rooms/bcdf-ghjk-mnpq");
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    @DisplayName("동일 출처와 인증 Account가 모두 있으면 뒤의 CSRF 경계로 요청을 넘긴다")
    void delegatesAuthenticatedSameOriginRequest() throws Exception {
        AuthenticatedAccountPrincipal principal = new AccountSessionPrincipal(UUID.randomUUID(), 0);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of())
        );
        MockHttpServletRequest request = sameOriginRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    @DisplayName("같은 Account와 방의 연속 갱신 요청은 429와 재시도 시간을 반환한다")
    void rateLimitsAuthenticatedAccountRoomBurst() throws Exception {
        AuthenticatedAccountPrincipal principal = new AccountSessionPrincipal(ACCOUNT_ID, 0);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of())
        );
        FilterChain chain = mock(FilterChain.class);

        for (int attempt = 0; attempt < 12; attempt += 1) {
            MockHttpServletRequest request = sameOriginRequest();
            request.setRemoteAddr("198.51.100." + (attempt + 1));
            filter.doFilter(request, new MockHttpServletResponse(), chain);
        }
        MockHttpServletRequest limitedRequest = sameOriginRequest();
        limitedRequest.setRemoteAddr("198.51.100.200");
        MockHttpServletResponse limitedResponse = new MockHttpServletResponse();

        filter.doFilter(limitedRequest, limitedResponse, chain);

        assertThat(limitedResponse.getStatus()).isEqualTo(429);
        assertThat(limitedResponse.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(limitedResponse.getHeader(HttpHeaders.RETRY_AFTER)).isNotBlank();
        assertThat(limitedResponse.getHeader(HttpHeaders.SET_COOKIE)).isNull();
        assertThat(limitedResponse.getContentAsString()).isEqualTo(
                "{\"code\":\"AUTH_RATE_LIMITED\","
                        + "\"message\":\"인증 요청이 너무 많습니다. 잠시 후 다시 시도해 주세요\"}"
        );
        verify(chain, times(12)).doFilter(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", PATH);
        request.setScheme("http");
        request.setServerName("localhost");
        request.setServerPort(80);
        return request;
    }

    private MockHttpServletRequest sameOriginRequest() {
        MockHttpServletRequest request = request();
        request.addHeader(HttpHeaders.ORIGIN, "http://localhost");
        request.addHeader("Sec-Fetch-Site", "same-origin");
        return request;
    }
}
