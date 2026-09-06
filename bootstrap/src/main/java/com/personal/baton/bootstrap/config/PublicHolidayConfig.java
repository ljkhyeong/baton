package com.personal.baton.bootstrap.config;

import com.personal.baton.adapter.out.external.holiday.KasiPublicHolidayClient;
import com.personal.baton.application.holiday.PublicHolidayCalendar;
import com.personal.baton.application.holiday.port.out.PublicHolidayClient;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.Assert;

@Configuration(proxyBeanMethods = false)
public class PublicHolidayConfig {
    @Bean
    @ConditionalOnBooleanProperty(prefix = "baton.holidays", name = "enabled")
    PublicHolidayClient publicHolidayClient(
            @Value("${baton.holidays.service-key:}") String serviceKey,
            KasiPublicHolidayClient.Factory factory,
            Clock clock) {
        Assert.hasText(serviceKey, "공휴일 조회를 켜려면 공공데이터포털 서비스 키가 필요합니다");
        return factory.create(serviceKey.strip(), clock);
    }

    @Bean
    @ConditionalOnBooleanProperty(prefix = "baton.holidays", name = "enabled",
            havingValue = false, matchIfMissing = true)
    PublicHolidayClient disabledPublicHolidayClient() {
        return year -> PublicHolidayCalendar.empty(year, PublicHolidayCalendar.Status.DISABLED);
    }
}
