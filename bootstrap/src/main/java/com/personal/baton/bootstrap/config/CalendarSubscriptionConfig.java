package com.personal.baton.bootstrap.config;

import com.personal.baton.application.calendar.CalendarSubscriptionService;
import com.personal.baton.application.calendar.port.in.CalendarSubscriptionUseCase;
import com.personal.baton.application.calendar.port.out.CalendarSubscriptionClient;
import com.personal.baton.application.calendar.port.out.CalendarSubscriptionStore;
import com.personal.baton.application.roundauth.ActiveAccountTeamMembershipVerifier;
import com.personal.baton.application.workspace.port.in.VerifyWorkspaceAccessUseCase;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class CalendarSubscriptionConfig {
    @Bean
    CalendarSubscriptionUseCase calendarSubscriptions(VerifyWorkspaceAccessUseCase access,
            ActiveAccountTeamMembershipVerifier memberships, CalendarSubscriptionStore store,
            CalendarSubscriptionClient client, Clock clock, CalendarIntegrationProperties properties,
            @Value("${baton.calendar.subscriptions-enabled:false}") boolean enabled) {
        if (enabled && (!properties.captureEnabled() || !properties.deliveryEnabled())) {
            throw new IllegalStateException("CAL 구독은 일정 캡처와 전달을 켠 뒤 사용할 수 있습니다");
        }
        return new CalendarSubscriptionService(access, memberships, store, client, clock, enabled);
    }
}
