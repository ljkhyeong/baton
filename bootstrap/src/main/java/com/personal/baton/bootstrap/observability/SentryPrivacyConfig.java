package com.personal.baton.bootstrap.observability;

import io.sentry.Sentry;
import io.sentry.IScopes;
import io.sentry.TypeCheckHint;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.sentry.SentryEvent;
import io.sentry.SentryOptions;
import io.sentry.protocol.SentryException;
import io.sentry.protocol.SentryStackFrame;
import io.sentry.protocol.SentryStackTrace;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerRequestObservationContext;

@Configuration(proxyBeanMethods = false)
public class SentryPrivacyConfig {

    @Bean
    ObservationHandler<ServerRequestObservationContext> sentryHttpErrors(IScopes scopes) {
        return new ObservationHandler<>() {
            @Override
            public boolean supportsContext(Observation.Context context) {
                return context instanceof ServerRequestObservationContext;
            }

            @Override
            public void onStop(ServerRequestObservationContext context) {
                if (context.getResponse() != null && context.getResponse().getStatus() >= 500
                        && context.getError() != null) {
                    scopes.captureException(context.getError());
                }
            }
        };
    }

    @Bean
    Sentry.OptionsConfiguration<SentryOptions> errorOnlySentryOptions() {
        return options -> {
            options.setSendDefaultPii(false);
            options.setEnableAutoSessionTracking(false);
            options.setAttachServerName(false);
            options.setSendClientReports(false);
            options.setMaxBreadcrumbs(0);
            options.setMaxRequestBodySize(SentryOptions.RequestSize.NONE);
            options.setTracesSampleRate(0.0);
            options.setProfilesSampleRate(0.0);
            options.setProfileSessionSampleRate(0.0);
            options.setTracePropagationTargets(List.of());
            options.getLogs().setEnabled(false);
            options.getMetrics().setEnabled(false);
            options.setBeforeSendTransaction((event, hint) -> null);
            options.setBeforeSend((event, hint) -> {
                hint.clearAttachments();
                // HTTP 오류는 응답 상태가 확정된 기존 Observation에서 한 번 수집한다.
                if (hint.get(TypeCheckHint.SPRING_RESOLVER_REQUEST) != null) {
                    return null;
                }
                if (event.getExceptions() == null || event.getExceptions().isEmpty()) {
                    return null;
                }
                SentryEvent safe = new SentryEvent(event.getTimestamp());
                safe.setEventId(event.getEventId());
                safe.setLevel(event.getLevel());
                safe.setPlatform("java");
                safe.setRelease(event.getRelease());
                safe.setEnvironment(event.getEnvironment());
                safe.setExceptions(event.getExceptions().stream()
                        .map(SentryPrivacyConfig::exceptionLocation).toList());
                return safe;
            });
        };
    }

    private static SentryException exceptionLocation(SentryException original) {
        SentryException safe = new SentryException();
        safe.setType(original.getType());
        safe.setModule(original.getModule());
        if (original.getStacktrace() != null && original.getStacktrace().getFrames() != null) {
            safe.setStacktrace(new SentryStackTrace(original.getStacktrace().getFrames().stream()
                    .map(SentryPrivacyConfig::frameLocation).toList()));
        }
        return safe;
    }

    private static SentryStackFrame frameLocation(SentryStackFrame original) {
        SentryStackFrame safe = new SentryStackFrame();
        safe.setFilename(original.getFilename());
        safe.setModule(original.getModule());
        safe.setFunction(original.getFunction());
        safe.setLineno(original.getLineno());
        safe.setInApp(original.isInApp());
        return safe;
    }
}
