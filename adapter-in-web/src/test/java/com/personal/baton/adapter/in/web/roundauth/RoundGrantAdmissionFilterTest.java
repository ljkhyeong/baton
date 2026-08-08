package com.personal.baton.adapter.in.web.roundauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.personal.baton.adapter.in.web.auth.AuthenticatedAccountPrincipal;
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

class RoundGrantAdmissionFilterTest {

    private static final String PATH =
            "/round/rooms/bcdf-ghjk-mnpq/participation-grant/refresh";

    private final RoundGrantAdmissionFilter filter = new RoundGrantAdmissionFilter();

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("cross-origin refresh는 인증 여부를 노출하지 않고 cookie를 유지한 채 거절한다")
    void rejectsCrossOriginBeforeAuthentication() throws Exception {
        MockHttpServletRequest request = request();
        request.addHeader(HttpHeaders.ORIGIN, "https://attacker.example");
        request.addHeader("Sec-Fetch-Site", "cross-site");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("REQUEST_FORBIDDEN");
        assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).isNull();
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    @DisplayName("동일 출처지만 session이 없으면 401과 room-scoped 만료 cookie를 반환한다")
    void clearsGrantCookieWithoutAccountSession() throws Exception {
        MockHttpServletRequest request = sameOriginRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).isNull();
        assertThat(response.getHeader(HttpHeaders.SET_COOKIE))
                .contains("Max-Age=0", "Path=/round/rooms/bcdf-ghjk-mnpq");
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    @DisplayName("동일 출처와 인증 Account가 모두 있으면 뒤의 CSRF 경계로 요청을 넘긴다")
    void delegatesAuthenticatedSameOriginRequest() throws Exception {
        AuthenticatedAccountPrincipal principal = UUID::randomUUID;
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of())
        );
        MockHttpServletRequest request = sameOriginRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
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
