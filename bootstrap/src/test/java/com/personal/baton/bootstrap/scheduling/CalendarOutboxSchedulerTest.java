package com.personal.baton.bootstrap.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.personal.baton.application.calendar.port.in.DispatchCalendarOutboxUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class CalendarOutboxSchedulerTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(
                    DispatchCalendarOutboxUseCase.class,
                    () -> mock(DispatchCalendarOutboxUseCase.class)
            )
            .withUserConfiguration(CalendarOutboxScheduler.class);

    @Test
    @DisplayName("CAL 전달 scheduler는 전달을 켠 동안에만 등록된다")
    void registersOnlyWhenDeliveryIsEnabled() {
        contextRunner
                .withPropertyValues("baton.calendar.delivery-enabled=true")
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .hasSingleBean(CalendarOutboxScheduler.class));
        contextRunner.run(context -> assertThat(context)
                .hasNotFailed()
                .doesNotHaveBean(CalendarOutboxScheduler.class));
    }
}
