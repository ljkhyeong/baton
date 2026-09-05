package com.personal.baton.bootstrap.scheduling;

import com.personal.baton.application.calendar.port.in.CalendarSubscriptionUseCase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnBooleanProperty(prefix = "baton.calendar", name = "delivery-enabled")
public class CalendarSubscriptionRevocationScheduler {
    private final CalendarSubscriptionUseCase subscriptions;
    public CalendarSubscriptionRevocationScheduler(CalendarSubscriptionUseCase subscriptions) { this.subscriptions = subscriptions; }
    @Scheduled(fixedDelayString = "${baton.calendar.subscription-revocation-interval:PT5S}")
    public void revokePending() { subscriptions.revokePending(); }
}
