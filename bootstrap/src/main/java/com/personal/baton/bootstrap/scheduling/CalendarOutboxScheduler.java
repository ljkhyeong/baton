package com.personal.baton.bootstrap.scheduling;

import com.personal.baton.application.calendar.port.in.DispatchCalendarOutboxUseCase;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnBooleanProperty(prefix = "baton.calendar", name = "delivery-enabled")
class CalendarOutboxScheduler {

    private static final Log log = LogFactory.getLog(CalendarOutboxScheduler.class);

    private final DispatchCalendarOutboxUseCase dispatchCalendarOutbox;

    CalendarOutboxScheduler(DispatchCalendarOutboxUseCase dispatchCalendarOutbox) {
        this.dispatchCalendarOutbox = dispatchCalendarOutbox;
    }

    @Scheduled(
            fixedDelayString = "${baton.calendar.dispatch-interval:PT10S}",
            initialDelayString = "${baton.calendar.dispatch-interval:PT10S}",
            scheduler = "calendarTaskScheduler"
    )
    void dispatchPending() {
        DispatchCalendarOutboxUseCase.DispatchResult result =
                dispatchCalendarOutbox.dispatchPending();
        if (result.hasFailures()) {
            log.warn("CAL outbox 전달에 실패가 있습니다. claimed="
                    + result.claimedCount() + ", delivered=" + result.deliveredCount()
                    + ", failed=" + result.failedCount());
        }
    }
}
