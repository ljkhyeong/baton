package com.personal.baton.adapter.in.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

public class RequestIdFilter extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(RequestIdFilter.class);

    public static final String HEADER_NAME = "X-Request-ID";
    public static final String MDC_KEY = "requestId";

    private static final String REQUEST_ATTRIBUTE =
            RequestIdFilter.class.getName() + ".requestId";
    private static final String SERVER_ERROR_LOGGED_ATTRIBUTE =
            RequestIdFilter.class.getName() + ".serverErrorLogged";
    private static final String API_ROOT = "/api/v1";

    private final Supplier<UUID> requestIdGenerator;

    public RequestIdFilter() {
        this(UUID::randomUUID);
    }

    public RequestIdFilter(Supplier<UUID> requestIdGenerator) {
        this.requestIdGenerator = Objects.requireNonNull(requestIdGenerator);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String requestId = requestId(request);
        if (requestId == null) {
            requestId = Objects.requireNonNull(requestIdGenerator.get()).toString();
            request.setAttribute(REQUEST_ATTRIBUTE, requestId);
        }

        if (!response.isCommitted()) {
            response.setHeader(HEADER_NAME, requestId);
        }

        String previousRequestId = MDC.get(MDC_KEY);
        MDC.put(MDC_KEY, requestId);
        try {
            filterChain.doFilter(request, response);
            logUnrecordedServerError(request, response);
        } catch (IOException | ServletException | RuntimeException exception) {
            logEscapedFailure(request, exception);
            throw exception;
        } catch (Error error) {
            logEscapedFailure(request, error);
            throw error;
        } finally {
            restoreMdc(previousRequestId);
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return requestId(request) == null && !isProductApiRequest(request);
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    static void markServerErrorLogged(HttpServletRequest request) {
        request.setAttribute(SERVER_ERROR_LOGGED_ATTRIBUTE, Boolean.TRUE);
    }

    private boolean isProductApiRequest(HttpServletRequest request) {
        String requestPath = request.getRequestURI().substring(request.getContextPath().length());
        return requestPath.equals(API_ROOT) || requestPath.startsWith(API_ROOT + "/");
    }

    private String requestId(HttpServletRequest request) {
        Object requestId = request.getAttribute(REQUEST_ATTRIBUTE);
        return requestId instanceof String value ? value : null;
    }

    private void logEscapedFailure(HttpServletRequest request, Throwable failure) {
        if (isServerErrorLogged(request)) {
            return;
        }
        markServerErrorLogged(request);
        LOG.error(
                "Exception escaped HTTP filter chain: method={}, path={}",
                request.getMethod(),
                request.getRequestURI(),
                failure
        );
    }

    private void logUnrecordedServerError(
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        if (response.getStatus() < 500 || isServerErrorLogged(request)) {
            return;
        }
        markServerErrorLogged(request);
        LOG.error(
                "HTTP request completed with an unrecorded server error: method={}, path={}, status={}",
                request.getMethod(),
                request.getRequestURI(),
                response.getStatus()
        );
    }

    private boolean isServerErrorLogged(HttpServletRequest request) {
        return Boolean.TRUE.equals(request.getAttribute(SERVER_ERROR_LOGGED_ATTRIBUTE));
    }

    private void restoreMdc(String previousRequestId) {
        if (previousRequestId == null) {
            MDC.remove(MDC_KEY);
            return;
        }
        MDC.put(MDC_KEY, previousRequestId);
    }
}
