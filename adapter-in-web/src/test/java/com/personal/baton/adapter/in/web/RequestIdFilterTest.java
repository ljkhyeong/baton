package com.personal.baton.adapter.in.web;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestIdFilterTest {

    private static final UUID GENERATED_REQUEST_ID =
            UUID.fromString("11111111-2222-4333-8444-555555555555");
    private static final String CLIENT_REQUEST_ID =
            "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee";
    private static final String REQUEST_ID_MDC_KEY = "requestId";

    @DisplayName("제품 API 요청은 외부 요청 ID를 신뢰하지 않고 서버 ID를 응답과 MDC에 사용한다")
    @Test
    void createsServerOwnedRequestIdAndRestoresMdc() throws Exception {
        RequestIdFilter filter = new RequestIdFilter(() -> GENERATED_REQUEST_ID);
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/api/v1/system/status");
        request.addHeader(RequestIdFilter.HEADER_NAME, CLIENT_REQUEST_ID);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> requestMdc = new AtomicReference<>();

        MDC.put(REQUEST_ID_MDC_KEY, "outer-request");
        try {
            filter.doFilter(request, response, (filteredRequest, filteredResponse) ->
                    requestMdc.set(MDC.get(REQUEST_ID_MDC_KEY)));

            assertThat(response.getHeader(RequestIdFilter.HEADER_NAME))
                    .isEqualTo(GENERATED_REQUEST_ID.toString())
                    .isNotEqualTo(CLIENT_REQUEST_ID);
            assertThat(requestMdc).hasValue(GENERATED_REQUEST_ID.toString());
            assertThat(MDC.get(REQUEST_ID_MDC_KEY)).isEqualTo("outer-request");
        } finally {
            MDC.remove(REQUEST_ID_MDC_KEY);
        }
    }

    @DisplayName("오류와 비동기 재디스패치는 최초 요청 ID를 다시 사용하고 MDC를 누출하지 않는다")
    @Test
    void reusesRequestIdAcrossErrorAndAsyncDispatches() throws Exception {
        AtomicInteger generationCount = new AtomicInteger();
        RequestIdFilter filter = new RequestIdFilter(() -> {
            generationCount.incrementAndGet();
            return GENERATED_REQUEST_ID;
        });
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/api/v1/workspaces");

        assertDispatchUsesRequestId(filter, request, new MockHttpServletResponse());

        request.setDispatcherType(DispatcherType.ERROR);
        request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, request.getRequestURI());
        assertDispatchUsesRequestId(filter, request, new MockHttpServletResponse());
        request.removeAttribute(RequestDispatcher.ERROR_REQUEST_URI);

        request.setDispatcherType(DispatcherType.ASYNC);
        assertDispatchUsesRequestId(filter, request, new MockHttpServletResponse());

        assertThat(generationCount).hasValue(1);
    }

    @DisplayName("제품 계약 밖의 Actuator 요청에는 제품 요청 ID를 추가하지 않는다")
    @Test
    void skipsNonProductApiRequest() throws Exception {
        AtomicInteger generationCount = new AtomicInteger();
        RequestIdFilter filter = new RequestIdFilter(() -> {
            generationCount.incrementAndGet();
            return GENERATED_REQUEST_ID;
        });
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/actuator/health");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> requestMdc = new AtomicReference<>("not-called");

        filter.doFilter(request, response, (filteredRequest, filteredResponse) ->
                requestMdc.set(MDC.get(REQUEST_ID_MDC_KEY)));

        assertThat(response.getHeader(RequestIdFilter.HEADER_NAME)).isNull();
        assertThat(requestMdc).hasValue(null);
        assertThat(generationCount).hasValue(0);
    }

    @DisplayName("ROUND 참여권 갱신도 제품 요청 ID를 응답과 MDC에 사용한다")
    @Test
    void createsRequestIdForParticipationGrantRefresh() throws Exception {
        RequestIdFilter filter = new RequestIdFilter(() -> GENERATED_REQUEST_ID);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST",
                "/round/rooms/bcdf-ghjk-mnpq/participation-grant/refresh"
        );
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> requestMdc = new AtomicReference<>();

        filter.doFilter(request, response, (filteredRequest, filteredResponse) ->
                requestMdc.set(MDC.get(REQUEST_ID_MDC_KEY)));

        assertThat(response.getHeader(RequestIdFilter.HEADER_NAME))
                .isEqualTo(GENERATED_REQUEST_ID.toString());
        assertThat(requestMdc).hasValue(GENERATED_REQUEST_ID.toString());
        assertThat(MDC.get(REQUEST_ID_MDC_KEY)).isNull();
    }

    @DisplayName("필터 체인 밖으로 탈출한 예외는 MDC가 살아 있을 때 요청 ID와 함께 한 번 기록한다")
    @Test
    void logsEscapedFailureBeforeRestoringMdc() {
        RequestIdFilter filter = new RequestIdFilter(() -> GENERATED_REQUEST_ID);
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/api/v1/workspaces");
        MockHttpServletResponse response = new MockHttpServletResponse();
        Logger logger = (Logger) LoggerFactory.getLogger(RequestIdFilter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            assertThatThrownBy(() -> filter.doFilter(
                    request,
                    response,
                    (filteredRequest, filteredResponse) -> {
                        throw new ServletException("escaped failure");
                    }
            )).isInstanceOf(ServletException.class);

            assertThat(appender.list).hasSize(1);
            assertThat(appender.list.getFirst().getMDCPropertyMap())
                    .containsEntry(REQUEST_ID_MDC_KEY, GENERATED_REQUEST_ID.toString());
            assertThat(appender.list.getFirst().getFormattedMessage())
                    .contains("method=GET", "path=/api/v1/workspaces");
            assertThat(MDC.get(REQUEST_ID_MDC_KEY)).isNull();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @DisplayName("예외 기록 없이 끝난 5xx 응답도 MDC 요청 ID와 함께 한 번 기록한다")
    @Test
    void logsUnrecordedServerErrorResponse() throws Exception {
        RequestIdFilter filter = new RequestIdFilter(() -> GENERATED_REQUEST_ID);
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/api/v1/workspaces");
        MockHttpServletResponse response = new MockHttpServletResponse();
        Logger logger = (Logger) LoggerFactory.getLogger(RequestIdFilter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            filter.doFilter(request, response, (filteredRequest, filteredResponse) ->
                    ((MockHttpServletResponse) filteredResponse).setStatus(503));

            assertThat(appender.list).hasSize(1);
            assertThat(appender.list.getFirst().getMDCPropertyMap())
                    .containsEntry(REQUEST_ID_MDC_KEY, GENERATED_REQUEST_ID.toString());
            assertThat(appender.list.getFirst().getFormattedMessage())
                    .contains("method=GET", "path=/api/v1/workspaces", "status=503");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @DisplayName("이미 커밋된 응답은 요청 ID 헤더를 다시 쓰지 않지만 MDC 경계는 유지한다")
    @Test
    void keepsCommittedResponseHeaderUntouched() throws Exception {
        RequestIdFilter filter = new RequestIdFilter(() -> GENERATED_REQUEST_ID);
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/api/v1/workspaces");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> requestMdc = new AtomicReference<>();
        response.setHeader(RequestIdFilter.HEADER_NAME, "committed-request-id");
        response.flushBuffer();

        filter.doFilter(request, response, (filteredRequest, filteredResponse) ->
                requestMdc.set(MDC.get(REQUEST_ID_MDC_KEY)));

        assertThat(response.getHeader(RequestIdFilter.HEADER_NAME))
                .isEqualTo("committed-request-id");
        assertThat(requestMdc).hasValue(GENERATED_REQUEST_ID.toString());
        assertThat(MDC.get(REQUEST_ID_MDC_KEY)).isNull();
    }

    private void assertDispatchUsesRequestId(
            RequestIdFilter filter,
            MockHttpServletRequest request,
            MockHttpServletResponse response
    ) throws Exception {
        AtomicReference<String> requestMdc = new AtomicReference<>();

        filter.doFilter(request, response, (filteredRequest, filteredResponse) ->
                requestMdc.set(MDC.get(REQUEST_ID_MDC_KEY)));

        assertThat(response.getHeader(RequestIdFilter.HEADER_NAME))
                .isEqualTo(GENERATED_REQUEST_ID.toString());
        assertThat(requestMdc).hasValue(GENERATED_REQUEST_ID.toString());
        assertThat(MDC.get(REQUEST_ID_MDC_KEY)).isNull();
    }
}
