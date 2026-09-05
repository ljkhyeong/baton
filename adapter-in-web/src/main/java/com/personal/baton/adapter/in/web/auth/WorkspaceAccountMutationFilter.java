package com.personal.baton.adapter.in.web.auth;

import com.personal.baton.adapter.in.web.ErrorResponse;
import com.personal.baton.adapter.in.web.security.AccountSessionRequestMatchers;
import com.personal.baton.adapter.in.web.security.SecurityErrorResponseWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

public final class WorkspaceAccountMutationFilter extends OncePerRequestFilter {
    private final SecurityErrorResponseWriter errors;
    public WorkspaceAccountMutationFilter(SecurityErrorResponseWriter errors) { this.errors = errors; }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !AccountSessionRequestMatchers.workspaceAccountMutation().matches(request);
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String expected = request.getHeader("X-Baton-Account-Id");
        if (CurrentAuthenticatedAccount.accountId().filter(id -> id.toString().equalsIgnoreCase(expected)).isEmpty()) {
            errors.write(response, HttpServletResponse.SC_FORBIDDEN,
                    new ErrorResponse("ACCOUNT_CHANGED", "로그인 계정이 바뀌었거나 확인되지 않았습니다. 새로고침한 뒤 다시 시도해 주세요"));
            return;
        }
        chain.doFilter(request, response);
    }
}
