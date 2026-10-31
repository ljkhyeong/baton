package com.personal.baton.adapter.in.web.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.personal.baton.adapter.in.web.security.SecurityErrorResponseWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.ForwardedHeaderFilter;
import tools.jackson.databind.ObjectMapper;

class LocalLoginRateLimitFilterTest {

    @DisplayName("local 로그인 제한은 표준 오류 JSON과 재시도 시간을 반환한다")
    @Test
    void writesCanonicalRateLimitResponse() throws Exception {
        LocalLoginRateLimitFilter filter = new LocalLoginRateLimitFilter(
                new AuthRateLimiter(),
                new SecurityErrorResponseWriter(new ObjectMapper())
        );

        for (int attempt = 0; attempt < 10; attempt += 1) {
            filter.doFilter(request(), new MockHttpServletResponse(), new MockFilterChain());
        }
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request(), response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(response.getHeader(HttpHeaders.RETRY_AFTER)).isNotBlank();
        assertThat(response.getContentAsString()).isEqualTo(
                "{\"code\":\"AUTH_RATE_LIMITED\","
                        + "\"message\":\"인증 요청이 너무 많습니다. 잠시 후 다시 시도해 주세요\"}"
        );
    }

    @DisplayName("프록시가 전달한 IPv6 클라이언트는 ForwardedHeaderFilter를 거쳐도 /64별로 제한한다")
    @Test
    void separatesForwardedIpv6Networks() throws Exception {
        LocalLoginRateLimitFilter filter = new LocalLoginRateLimitFilter(
                new AuthRateLimiter(),
                new SecurityErrorResponseWriter(new ObjectMapper())
        );
        ForwardedHeaderFilter forwardedHeaderFilter = new ForwardedHeaderFilter();

        for (int attempt = 0; attempt < 60; attempt += 1) {
            forwarded(filter, forwardedHeaderFilter, "2001:db8:abcd:42::1", "member-" + attempt + "@example.com");
        }

        assertThat(forwarded(filter, forwardedHeaderFilter, "2001:db8:abcd:42::2", "another@example.com").getStatus())
                .isEqualTo(429);
        assertThat(forwarded(filter, forwardedHeaderFilter, "2001:db8:abcd:43::1", "another@example.com").getStatus())
                .isEqualTo(200);
    }

    private MockHttpServletResponse forwarded(
            LocalLoginRateLimitFilter filter,
            ForwardedHeaderFilter forwardedHeaderFilter,
            String clientAddress,
            String email
    ) throws Exception {
        MockHttpServletRequest request = request();
        request.setParameter("email", email);
        request.addHeader("X-Forwarded-For", clientAddress);
        MockHttpServletResponse response = new MockHttpServletResponse();
        forwardedHeaderFilter.doFilter(
                request,
                response,
                (forwardedRequest, forwardedResponse) -> filter.doFilter(
                        forwardedRequest,
                        forwardedResponse,
                        new MockFilterChain()
                )
        );
        return response;
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST",
                AuthController.LOCAL_SESSION_PATH
        );
        request.setRemoteAddr("198.51.100.1");
        request.addParameter("email", "member@example.com");
        return request;
    }
}
