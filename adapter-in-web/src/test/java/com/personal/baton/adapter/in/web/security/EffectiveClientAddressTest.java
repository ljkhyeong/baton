package com.personal.baton.adapter.in.web.security;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.ForwardedHeaderFilter;

import static org.assertj.core.api.Assertions.assertThat;

class EffectiveClientAddressTest {

    @DisplayName("직접 요청의 주입된 X-Forwarded-For는 client 주소로 신뢰하지 않는다")
    @Test
    void ignoresUnprocessedForwardedHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("198.51.100.8");
        request.addHeader("X-Forwarded-For", "203.0.113.99");

        assertThat(EffectiveClientAddress.resolve(request)).isEqualTo("198.51.100.8");
    }

    @DisplayName("FRAMEWORK ForwardedHeaderFilter가 적용한 client 주소는 remoteAddr로 사용한다")
    @Test
    void usesAddressProcessedByFrameworkForwardedHeaderFilter() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("172.19.0.2");
        request.addHeader("X-Forwarded-For", "203.0.113.8");
        request.addHeader("X-Forwarded-Proto", "https");
        AtomicReference<String> resolvedAddress = new AtomicReference<>();

        new ForwardedHeaderFilter().doFilter(
                request,
                new MockHttpServletResponse(),
                (forwardedRequest, ignored) -> resolvedAddress.set(
                        EffectiveClientAddress.resolve(
                                (jakarta.servlet.http.HttpServletRequest) forwardedRequest
                        )
                )
        );

        assertThat(resolvedAddress.get()).isEqualTo("203.0.113.8");
    }
}
