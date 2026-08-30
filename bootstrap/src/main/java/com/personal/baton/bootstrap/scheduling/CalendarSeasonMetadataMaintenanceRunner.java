package com.personal.baton.bootstrap.scheduling;

import com.personal.baton.application.calendar.port.in.MaintainCalendarSeasonMetadataUseCase;
import com.personal.baton.application.calendar.port.in.MaintainCalendarSeasonMetadataUseCase.Mode;
import com.personal.baton.bootstrap.config.CalendarIntegrationProperties;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.stereotype.Component;

@Component
@DependsOnDatabaseInitialization
class CalendarSeasonMetadataMaintenanceRunner implements ApplicationRunner {

    private static final Log log = LogFactory.getLog(CalendarSeasonMetadataMaintenanceRunner.class);
    private final MaintainCalendarSeasonMetadataUseCase maintenance;
    private final CalendarIntegrationProperties properties;

    CalendarSeasonMetadataMaintenanceRunner(MaintainCalendarSeasonMetadataUseCase maintenance, CalendarIntegrationProperties properties) {
        this.maintenance = maintenance;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (properties.seasonMetadataMaintenance() == Mode.OFF) {
            return;
        }
        var result = maintenance.maintain(properties.seasonMetadataMaintenance());
        log.info("CAL 시즌 이름 전달 준비를 완료했습니다. 모드=" + properties.seasonMetadataMaintenance()
                + ", 대상=" + result.candidateCount() + ", 추가=" + result.appendedCount()
                + ", 재전달 대기=" + result.requeuedCount());
    }
}
