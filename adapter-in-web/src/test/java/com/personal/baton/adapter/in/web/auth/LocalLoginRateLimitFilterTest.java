package com.personal.baton.adapter.in.web.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.personal.baton.adapter.in.web.security.SecurityErrorResponseWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
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
