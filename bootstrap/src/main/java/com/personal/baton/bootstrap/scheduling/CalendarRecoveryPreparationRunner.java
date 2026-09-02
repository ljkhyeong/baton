package com.personal.baton.bootstrap.scheduling;

import com.personal.baton.application.calendar.port.out.CalendarRecoveryStatePort;
import java.time.Clock;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@DependsOnDatabaseInitialization
@Order(30)
@ConditionalOnBooleanProperty(prefix = "baton.calendar", name = "recovery-preparation-enabled")
class CalendarRecoveryPreparationRunner implements ApplicationRunner {

    private static final Log log = LogFactory.getLog(CalendarRecoveryPreparationRunner.class);

    private final CalendarRecoveryStatePort recoveryState;
    private final Clock clock;

    CalendarRecoveryPreparationRunner(CalendarRecoveryStatePort recoveryState, Clock clock) {
        this.recoveryState = recoveryState;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        int requeued = recoveryState.requeueLatestSnapshots(clock.instant());
        log.info("CAL 전체 일정 재전달 준비를 완료했습니다. 재전달 대기=" + requeued);
    }
}
