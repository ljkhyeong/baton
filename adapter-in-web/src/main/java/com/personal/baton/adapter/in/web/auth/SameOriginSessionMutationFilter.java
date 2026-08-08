package com.personal.baton.adapter.in.web.auth;

import com.personal.baton.adapter.in.web.roundauth.RoundAdministrationController;
import com.personal.baton.adapter.in.web.security.SameOriginRequestPolicy;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

public final class SameOriginSessionMutationFilter extends OncePerRequestFilter {

    private static final String AUTH_ROOT = "/api/v1/auth/";
    private static final String FORBIDDEN_RESPONSE =
            "{\"code\":\"ORIGIN_DENIED\",\"message\":\"동일 출처 요청만 허용됩니다\"}";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !isProtectedMutation(request.getMethod(), path);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (!SameOriginRequestPolicy.allows(request)) {
            writeForbidden(response);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean isProtectedMutation(String method, String path) {
        if (HttpMethod.POST.matches(method) && path.startsWith(AUTH_ROOT)) {
            return true;
        }
        if (HttpMethod.POST.matches(method)) {
            return RoundAdministrationController.MEMBERSHIP_CLAIMS_PATH.equals(path)
                    || RoundAdministrationController.ROOM_MAPPINGS_PATH.equals(path);
        }
        return HttpMethod.DELETE.matches(method)
                && path.startsWith(RoundAdministrationController.ROOM_MAPPINGS_PATH + "/");
    }

    private void writeForbidden(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write(FORBIDDEN_RESPONSE);
    }
}
