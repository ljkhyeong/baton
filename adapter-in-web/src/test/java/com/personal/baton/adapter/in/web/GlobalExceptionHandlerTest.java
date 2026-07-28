package com.personal.baton.adapter.in.web;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.filter.ServerHttpObservationFilter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class GlobalExceptionHandlerTest {

    private static final UUID REQUEST_ID =
            UUID.fromString("11111111-2222-4333-8444-555555555555");

    private MockMvc mockMvc;
    private AtomicReference<ServerRequestObservationContext> stoppedObservation;
    private Logger exceptionLogger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        exceptionLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        exceptionLogger.addAppender(logAppender);

        stoppedObservation = new AtomicReference<>();
        ObservationRegistry observationRegistry = ObservationRegistry.create();
        observationRegistry.observationConfig().observationHandler(
                new ObservationHandler<ServerRequestObservationContext>() {
                    @Override
                    public boolean supportsContext(Observation.Context context) {
                        return context instanceof ServerRequestObservationContext;
                    }

                    @Override
                    public void onStop(ServerRequestObservationContext context) {
                        stoppedObservation.set(context);
                    }
                }
        );
        mockMvc = standaloneSetup(new ErrorFixtureController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(
                        new RequestIdFilter(() -> REQUEST_ID),
                        new ServerHttpObservationFilter(observationRegistry)
                )
                .build();
    }

    @AfterEach
    void tearDown() {
        exceptionLogger.detachAppender(logAppender);
        logAppender.stop();
    }

    @DisplayName("필수 요청 헤더가 없으면 공통 입력 오류로 응답한다")
    @Test
    void handlesMissingRequestHeader() throws Exception {
        mockMvc.perform(get("/api/v1/test/errors"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.message").value("요청 값이 올바르지 않습니다"));

        assertThat(stoppedObservation.get()).isNotNull();
        assertThat(stoppedObservation.get().getError())
                .isInstanceOf(MissingRequestHeaderException.class);
    }

    @DisplayName("존재하지 않는 경로는 안정적인 404 오류로 응답한다")
    @Test
    void handlesMissingResource() throws Exception {
        mockMvc.perform(get("/api/v1/test/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("요청한 경로를 찾을 수 없습니다"));
    }

    @DisplayName("지원하지 않는 HTTP 메서드는 안정적인 405 오류로 응답한다")
    @Test
    void handlesUnsupportedHttpMethod() throws Exception {
        MvcResult result = mockMvc.perform(delete("/api/v1/test/errors"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.message").value("지원하지 않는 HTTP 메서드입니다"))
                .andReturn();

        assertThat(result.getResponse().getHeader(HttpHeaders.ALLOW))
                .contains("GET", "POST");
    }

    @DisplayName("지원하지 않는 요청 본문 형식은 안정적인 415 오류로 응답한다")
    @Test
    void handlesUnsupportedMediaType() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/test/errors")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("plain text"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"))
                .andExpect(jsonPath("$.message").value("지원하지 않는 요청 본문 형식입니다"))
                .andReturn();

        assertThat(result.getResponse().getHeader(HttpHeaders.ACCEPT))
                .contains(MediaType.APPLICATION_JSON_VALUE);
    }

    @DisplayName("해석할 수 없는 JSON 본문은 안전한 입력 오류로 응답한다")
    @Test
    void handlesUnreadableJsonBody() throws Exception {
        mockMvc.perform(post("/api/v1/test/errors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.message").value("요청 본문 형식이 올바르지 않습니다"));
    }

    @DisplayName("제공할 수 없는 응답 형식은 본문 없는 406 오류로 응답한다")
    @Test
    void handlesUnacceptableResponseType() throws Exception {
        mockMvc.perform(get("/api/v1/test/errors/representation")
                        .accept(MediaType.APPLICATION_XML))
                .andExpect(status().isNotAcceptable())
                .andExpect(content().string(""));
    }

    @DisplayName("예상하지 못한 예외는 내부 정보를 숨기고 HTTP 관측에 기록한다")
    @Test
    void hidesUnexpectedFailureAndRecordsObservationError() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/test/errors/failure"))
                .andExpect(status().isInternalServerError())
                .andExpect(header().string(
                        RequestIdFilter.HEADER_NAME,
                        REQUEST_ID.toString()
                ))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("서버에서 요청을 처리하지 못했습니다"))
                .andReturn();

        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("jdbc:mysql://secret-host/internal");
        assertThat(stoppedObservation.get()).isNotNull();
        assertThat(stoppedObservation.get().getError())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("jdbc:mysql://secret-host/internal");
        assertThat(logAppender.list)
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.getMDCPropertyMap())
                            .containsEntry(RequestIdFilter.MDC_KEY, REQUEST_ID.toString());
                    assertThat(event.getFormattedMessage())
                            .contains("method=GET", "path=/api/v1/test/errors/failure");
                });
    }

    @RestController
    private static class ErrorFixtureController {

        @GetMapping("/api/v1/test/errors")
        String read(@RequestHeader("X-Test-Value") String value) {
            return value;
        }

        @PostMapping(value = "/api/v1/test/errors", consumes = MediaType.APPLICATION_JSON_VALUE)
        String write(@RequestBody ErrorPayload payload) {
            return payload.value();
        }

        @GetMapping(
                value = "/api/v1/test/errors/representation",
                produces = MediaType.APPLICATION_JSON_VALUE
        )
        ErrorPayload representation() {
            return new ErrorPayload("ok");
        }

        @GetMapping("/api/v1/test/errors/failure")
        String fail() {
            throw new IllegalStateException("jdbc:mysql://secret-host/internal");
        }
    }

    private record ErrorPayload(String value) {
    }
}
