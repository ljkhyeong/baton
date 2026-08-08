package com.personal.baton.adapter.in.web.roundauth;

import com.personal.baton.adapter.in.web.auth.AuthenticatedAccountPrincipal;
import com.personal.baton.adapter.in.web.security.SameOriginRequestPolicy;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public final class RoundGrantAdmissionFilter extends OncePerRequestFilter {

    private static final String PREFIX = "/round/rooms/";
    private static final String SUFFIX = "/participation-grant/refresh";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!HttpMethod.POST.matches(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.startsWith(PREFIX) || !path.endsWith(SUFFIX);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (!SameOriginRequestPolicy.allows(request)) {
            writeError(
                    response,
                    HttpServletResponse.SC_FORBIDDEN,
                    "REQUEST_FORBIDDEN",
                    "동일 출처 요청만 허용됩니다"
            );
            return;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof AuthenticatedAccountPrincipal)) {
            String roomId = roomId(request);
            if (roomId != null) {
                response.addHeader(
                        HttpHeaders.SET_COOKIE,
                        RoundGrantCookie.expire(roomId).toString()
                );
            }
            writeError(
                    response,
                    HttpServletResponse.SC_UNAUTHORIZED,
                    "AUTHENTICATION_REQUIRED",
                    "BATON 계정 로그인이 필요합니다"
            );
            return;
        }
        filterChain.doFilter(request, response);
    }

    private String roomId(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        String candidate = path.substring(PREFIX.length(), path.length() - SUFFIX.length());
        try {
            RoundGrantCookie.path(candidate);
            return candidate;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private void writeError(
            HttpServletResponse response,
            int status,
            String code,
            String message
    ) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.getWriter().write(
                "{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}"
        );
    }
}
