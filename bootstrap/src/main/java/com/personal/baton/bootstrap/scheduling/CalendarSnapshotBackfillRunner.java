package com.personal.baton.bootstrap.scheduling;

import com.personal.baton.application.calendar.port.in.BackfillCalendarSnapshotsUseCase;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.stereotype.Component;

@Component
@DependsOnDatabaseInitialization
@ConditionalOnBooleanProperty(prefix = "baton.calendar", name = "backfill-enabled")
class CalendarSnapshotBackfillRunner implements ApplicationRunner {

    private static final Log log = LogFactory.getLog(CalendarSnapshotBackfillRunner.class);

    private final BackfillCalendarSnapshotsUseCase backfillCalendarSnapshots;

    CalendarSnapshotBackfillRunner(
            BackfillCalendarSnapshotsUseCase backfillCalendarSnapshots
    ) {
        this.backfillCalendarSnapshots = backfillCalendarSnapshots;
    }

    @Override
    public void run(ApplicationArguments args) {
        BackfillCalendarSnapshotsUseCase.BackfillResult result =
                backfillCalendarSnapshots.backfill();
        log.info("CAL 기존 일정 보정을 완료했습니다. 대상="
                + result.candidateCount() + ", 추가=" + result.appendedCount());
    }
}
