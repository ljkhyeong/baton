package com.personal.baton.bootstrap.scheduling;

import com.personal.baton.application.calendar.port.in.CompleteCalendarRecoveryUseCase;
import com.personal.baton.bootstrap.config.CalendarIntegrationProperties;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnBooleanProperty(prefix = "baton.calendar", name = "delivery-enabled")
class CalendarRecoveryCompletionScheduler {

    private static final Log log = LogFactory.getLog(CalendarRecoveryCompletionScheduler.class);

    private final CompleteCalendarRecoveryUseCase recovery;
    private final UUID recoveryId;
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final AtomicReference<String> lastWaitingCode = new AtomicReference<>();

    CalendarRecoveryCompletionScheduler(
            CompleteCalendarRecoveryUseCase recovery,
            CalendarIntegrationProperties properties
    ) {
        this.recovery = recovery;
        this.recoveryId = properties.parsedRecoveryRunId().orElse(null);
    }

    @Scheduled(
            fixedDelayString = "${baton.calendar.dispatch-interval:PT10S}",
            initialDelayString = "${baton.calendar.dispatch-interval:PT10S}",
            scheduler = "calendarTaskScheduler"
    )
    void completeRecovery() {
        if (recoveryId == null || stopped.get()) {
            return;
        }
        var result = recovery.complete(recoveryId);
        switch (result.status()) {
            case WAITING -> {
                // 주기마다 반복하지 않고 대기 사유가 달라질 때만 남겨 불일치 종류를 잃지 않는다.
                if (!Objects.equals(result.code(), lastWaitingCode.getAndSet(result.code()))) {
                    log.warn("CAL 전체 복구 완료를 기다립니다. code=" + result.code());
                }
                return;
            }
            case COMPLETED -> log.info(
                    "CAL 전체 복구 완료 신호를 확인했습니다. 시즌=" + result.seasonCount()
            );
            case FAILED -> log.error(
                    "CAL 전체 복구 완료 확인이 거부되었습니다. code=" + result.code()
            );
        }
        stopped.set(true);
    }
}
