package com.personal.baton.bootstrap.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.sentry.Attachment;
import io.sentry.Hint;
import io.sentry.IScopes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import io.sentry.JsonSerializer;
import io.sentry.SentryEvent;
import io.sentry.SentryOptions;
import io.sentry.protocol.SentryException;
import io.sentry.protocol.SentryStackFrame;
import io.sentry.protocol.SentryStackTrace;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.observation.ServerRequestObservationContext;

class SentryPrivacyConfigTest {

    @Test
    @DisplayName("HTTP Observation은 응답이 확정된 5xx 오류만 보고한다")
    void capturesOnlyServerErrors() {
        IScopes scopes = mock(IScopes.class);
        var observer = new SentryPrivacyConfig().sentryHttpErrors(scopes);
        var response = mock(HttpServletResponse.class);
        var context = new ServerRequestObservationContext(mock(HttpServletRequest.class), response);
        var failure = new IllegalStateException("private-data");
        context.setError(failure);
        when(response.getStatus()).thenReturn(400);
        observer.onStop(context);
        verifyNoInteractions(scopes);
        when(response.getStatus()).thenReturn(500);
        observer.onStop(context);
        verify(scopes).captureException(failure);
    }

    @Test
    @DisplayName("Sentry 전송에서 인증값·입력값·지역 변수를 제거하고 오류 위치를 보존한다")
    void reportsOnlyErrorLocations() throws Exception {
        SentryOptions options = new SentryOptions();
        new SentryPrivacyConfig().errorOnlySentryOptions().configure(options);
        SentryEvent event = new SentryEvent();
        event.setExtra("token", "secret-token");
        event.setTransaction("/verify-email#token=secret-token");
        event.setTag("email", "member@example.com");
        event.getContexts().put("form", Map.of("password", "secret-password"));
        SentryException exception = new SentryException();
        exception.setType("IllegalStateException");
        exception.setValue("member@example.com secret-token");
        SentryStackFrame frame = new SentryStackFrame();
        frame.setFilename("AccountService.java");
        frame.setLineno(42);
        frame.setVars(Map.of("token", "secret-token"));
        frame.setContextLine("secret-password");
        exception.setStacktrace(new SentryStackTrace(List.of(frame)));
        event.setExceptions(List.of(exception));
        Hint hint = new Hint();
        hint.addAttachment(new Attachment("secret-token".getBytes(), "request.txt"));

        SentryEvent safe = options.getBeforeSend().execute(event, hint);
        StringWriter json = new StringWriter();
        new JsonSerializer(options).serialize(safe, json);

        assertThat(json.toString()).contains("IllegalStateException", "AccountService.java", "42")
                .doesNotContain("secret-token", "secret-password", "member@example.com", "verify-email");
        assertThat(hint.getAttachments()).isEmpty();
        assertThat(options.getLogs().isEnabled()).isFalse();
        assertThat(options.isEnableAutoSessionTracking()).isFalse();
        assertThat(options.getBeforeSend().execute(new SentryEvent(), new Hint())).isNull();
    }
}
