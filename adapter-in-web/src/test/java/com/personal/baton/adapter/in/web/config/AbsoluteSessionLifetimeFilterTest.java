package com.personal.baton.adapter.in.web.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AbsoluteSessionLifetimeFilterTest {

    private static final Instant CREATED_AT = Instant.parse("2026-07-30T00:00:00Z");
    private static final Instant AUTHENTICATED_AT =
            Instant.parse("2026-07-30T01:00:00Z");
    private static final Duration ABSOLUTE_LIFETIME = Duration.ofHours(12);

    @DisplayName("절대 수명에 도달한 세션은 요청을 처리하기 전에 무효화한다")
    @Test
    void invalidatesSessionAtAbsoluteLifetime() throws Exception {
        AbsoluteSessionLifetimeFilter filter =
                filterAt(AUTHENTICATED_AT.plus(ABSOLUTE_LIFETIME));
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        HttpSession session = mock(HttpSession.class);
        FilterChain chain = mock(FilterChain.class);
        when(session.getCreationTime()).thenReturn(CREATED_AT.toEpochMilli());
        when(session.getAttribute(
                AbsoluteSessionLifetimeFilter.AUTHENTICATED_AT_SESSION_ATTRIBUTE
        )).thenReturn(AUTHENTICATED_AT.toEpochMilli());
        request.setSession(session);

        filter.doFilter(request, response, chain);

        verify(session).invalidate();
        verify(chain).doFilter(request, response);
    }

    @DisplayName("절대 수명 전의 세션은 그대로 요청을 처리한다")
    @Test
    void preservesSessionBeforeAbsoluteLifetime() throws Exception {
        AbsoluteSessionLifetimeFilter filter =
                filterAt(AUTHENTICATED_AT.plus(ABSOLUTE_LIFETIME).minusMillis(1));
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        HttpSession session = mock(HttpSession.class);
        FilterChain chain = mock(FilterChain.class);
        when(session.getCreationTime()).thenReturn(CREATED_AT.toEpochMilli());
        when(session.getAttribute(
                AbsoluteSessionLifetimeFilter.AUTHENTICATED_AT_SESSION_ATTRIBUTE
        )).thenReturn(AUTHENTICATED_AT.toEpochMilli());
        request.setSession(session);

        filter.doFilter(request, response, chain);

        verify(session, never()).invalidate();
        verify(chain).doFilter(request, response);
    }

    @DisplayName("로그인 기준점이 있으면 인증 전 세션 생성 시간은 절대 수명에서 제외한다")
    @Test
    void startsAbsoluteLifetimeAtAuthentication() throws Exception {
        AbsoluteSessionLifetimeFilter filter =
                filterAt(CREATED_AT.plus(ABSOLUTE_LIFETIME));
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        HttpSession session = mock(HttpSession.class);
        FilterChain chain = mock(FilterChain.class);
        when(session.getCreationTime()).thenReturn(CREATED_AT.toEpochMilli());
        when(session.getAttribute(
                AbsoluteSessionLifetimeFilter.AUTHENTICATED_AT_SESSION_ATTRIBUTE
        )).thenReturn(AUTHENTICATED_AT.toEpochMilli());
        request.setSession(session);

        filter.doFilter(request, response, chain);

        verify(session, never()).invalidate();
        verify(chain).doFilter(request, response);
    }

    @DisplayName("기존 인증 세션은 로그인 기준점이 없으면 세션 생성 시간으로 보수적으로 만료한다")
    @Test
    void fallsBackToCreationTimeForExistingSession() throws Exception {
        AbsoluteSessionLifetimeFilter filter =
                filterAt(CREATED_AT.plus(ABSOLUTE_LIFETIME));
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        HttpSession session = mock(HttpSession.class);
        FilterChain chain = mock(FilterChain.class);
        when(session.getCreationTime()).thenReturn(CREATED_AT.toEpochMilli());
        request.setSession(session);

        filter.doFilter(request, response, chain);

        verify(session).invalidate();
        verify(chain).doFilter(request, response);
    }

    @DisplayName("세션이 없는 공개 요청은 새 세션을 만들지 않는다")
    @Test
    void doesNotCreateSessionForSessionlessRequest() throws Exception {
        AbsoluteSessionLifetimeFilter filter = filterAt(CREATED_AT);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        org.assertj.core.api.Assertions.assertThat(request.getSession(false)).isNull();
    }

    private AbsoluteSessionLifetimeFilter filterAt(Instant now) {
        return new AbsoluteSessionLifetimeFilter(
                Clock.fixed(now, ZoneOffset.UTC),
                ABSOLUTE_LIFETIME
        );
    }
}
