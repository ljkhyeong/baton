package com.personal.baton.bootstrap.config;

import com.personal.baton.application.calendar.port.out.CalendarOutboxPort;
import com.personal.baton.application.calendar.port.out.CalendarOutboxPort.OperationalStatus;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.util.Locale;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class CalendarOutboxMetricsConfig {

    @Bean
    MeterBinder calendarOutboxMeterBinder(CalendarOutboxPort outboxPort) {
        return registry -> {
            for (OperationalStatus status : OperationalStatus.values()) {
                Gauge.builder(
                                "baton.calendar.outbox.entries",
                                outboxPort,
                                port -> port.countByOperationalStatus(status)
                        )
                        .description("상태별 CAL 아웃박스 행 수")
                        .tag("status", status.name().toLowerCase(Locale.ROOT))
                        .register(registry);
            }
        };
    }
}
