package com.personal.baton.bootstrap.scheduling;

import com.personal.baton.adapter.out.external.calendar.RestClientCalendarClient;
import com.personal.baton.application.calendar.port.in.MaintainCalendarSeasonMetadataUseCase;
import com.personal.baton.application.calendar.port.in.MaintainCalendarSeasonMetadataUseCase.Mode;
import com.personal.baton.application.calendar.port.in.MaintainCalendarSeasonMetadataUseCase.Result;
import com.personal.baton.bootstrap.config.CalendarIntegrationConfig;
import com.personal.baton.bootstrap.config.CalendarIntegrationProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CalendarSeasonMetadataMaintenanceRunnerTest {

    private final MaintainCalendarSeasonMetadataUseCase maintenance = mock(MaintainCalendarSeasonMetadataUseCase.class);
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(CalendarIntegrationConfig.class, CalendarSeasonMetadataMaintenanceRunner.class)
            .withBean(MaintainCalendarSeasonMetadataUseCase.class, () -> maintenance)
            .withBean(RestClientCalendarClient.Factory.class, () -> mock(RestClientCalendarClient.Factory.class));

    @Test
    @DisplayName("기본 설정에서는 시즌 이름 보정과 재전달을 실행하지 않는다")
    void defaultsToOff() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(CalendarIntegrationProperties.class).seasonMetadataMaintenance()).isEqualTo(Mode.OFF);
            context.getBean(CalendarSeasonMetadataMaintenanceRunner.class).run(new DefaultApplicationArguments());
            verifyNoInteractions(maintenance);
        });
    }

    @ParameterizedTest
    @EnumSource(value = Mode.class, names = {"BACKFILL", "REPLAY"})
    @DisplayName("명시한 보정 모드를 기동 시 한 번 실행한다")
    void runsSelectedMode(Mode mode) {
        when(maintenance.maintain(mode)).thenReturn(new Result(2, 1, 1));
        contextRunner.withPropertyValues(
                "baton.calendar.capture-enabled=true",
                "baton.calendar.season-metadata-enabled=true",
                "baton.calendar.season-metadata-maintenance=" + mode
        ).run(context -> {
            assertThat(context).hasNotFailed();
            context.getBean(CalendarSeasonMetadataMaintenanceRunner.class).run(new DefaultApplicationArguments());
            verify(maintenance).maintain(mode);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "baton.calendar.capture-enabled=false",
            "baton.calendar.season-metadata-enabled=false",
            "baton.calendar.delivery-enabled=true"
    })
    @DisplayName("캡처·이름 연동이 꺼지거나 전달이 켜져 있으면 보정 전에 기동을 거부한다")
    void rejectsUnsafeMaintenanceSettings(String override) {
        contextRunner.withPropertyValues(
                "baton.calendar.capture-enabled=true",
                "baton.calendar.season-metadata-enabled=true",
                "baton.calendar.season-metadata-maintenance=REPLAY",
                override
        ).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage(
                    "CAL 이름 보정·재전달 준비는 이름 연동과 캡처를 켜고 전달을 끈 상태에서 실행해야 합니다");
            verifyNoInteractions(maintenance);
        });
    }
}
