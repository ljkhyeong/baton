package com.personal.baton.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.personal.baton.adapter.out.external.holiday.KasiPublicHolidayClient;
import com.personal.baton.application.holiday.PublicHolidayCalendar.Status;
import com.personal.baton.application.holiday.port.out.PublicHolidayClient;
import java.time.Clock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class PublicHolidayConfigTest {
    private final KasiPublicHolidayClient.Factory factory = mock(KasiPublicHolidayClient.Factory.class);
    private final Clock clock = Clock.systemUTC();
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(Clock.class, () -> clock)
            .withBean(KasiPublicHolidayClient.Factory.class, () -> factory)
            .withUserConfiguration(PublicHolidayConfig.class);

    @Test
    @DisplayName("공휴일 연동은 키 없이 기본 비활성으로 실행한다")
    void startsDisabled() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(PublicHolidayClient.class);
            assertThat(context.getBean(PublicHolidayClient.class).find(2026).status()).isEqualTo(Status.DISABLED);
            verifyNoInteractions(factory);
        });
    }

    @Test
    @DisplayName("공휴일 연동을 켜면 설정한 키로 클라이언트를 조립한다")
    void enablesConfiguredClient() {
        var client = mock(KasiPublicHolidayClient.class);
        when(factory.create("test-key", clock)).thenReturn(client);
        runner.withPropertyValues("baton.holidays.enabled=true", "baton.holidays.service-key=test-key")
                .run(context -> assertThat(context).hasNotFailed().getBean(PublicHolidayClient.class).isSameAs(client));
    }

    @Test
    @DisplayName("공휴일 연동을 켜고 키를 빠뜨리면 시작 시 안내한다")
    void rejectsMissingKey() {
        runner.withPropertyValues("baton.holidays.enabled=true").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage("공휴일 조회를 켜려면 공공데이터포털 서비스 키가 필요합니다");
        });
    }
}
