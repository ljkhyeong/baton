package com.personal.baton.bootstrap.scheduling;

import com.personal.baton.application.watch.port.in.RecoverWatchMonitorOutboxUseCase;
import com.personal.baton.bootstrap.config.WatchEventReceiverProperties;
import com.personal.baton.bootstrap.config.WatchIntegrationProperties;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.stereotype.Component;

@Component
@DependsOnDatabaseInitialization
class WatchMonitorStartupRecovery implements InitializingBean {

    private static final Log log = LogFactory.getLog(WatchMonitorStartupRecovery.class);

    private final RecoverWatchMonitorOutboxUseCase recoverWatchMonitorOutbox;
    private final WatchIntegrationProperties watchProperties;
    private final WatchEventReceiverProperties receiverProperties;

    WatchMonitorStartupRecovery(
            RecoverWatchMonitorOutboxUseCase recoverWatchMonitorOutbox,
            WatchIntegrationProperties watchProperties,
            WatchEventReceiverProperties receiverProperties
    ) {
        this.recoverWatchMonitorOutbox = recoverWatchMonitorOutbox;
        this.watchProperties = watchProperties;
        this.receiverProperties = receiverProperties;
    }

    @Override
    public void afterPropertiesSet() {
        recoverOnStartup();
    }

    void recoverOnStartup() {
        if (!watchProperties.enabled() && !receiverProperties.enabled()) {
            return;
        }

        recoverWatchMonitorOutbox.validateSourceNamespace();
        if (!watchProperties.enabled()) {
            return;
        }

        int requeuedCount = recoverWatchMonitorOutbox.requeueOperationalFailures();
        if (requeuedCount > 0) {
            log.info("WATCH 설정·경로 오류로 실패한 outbox를 시작 시점에 재처리합니다. requeued="
                    + requeuedCount);
        }
    }
}
