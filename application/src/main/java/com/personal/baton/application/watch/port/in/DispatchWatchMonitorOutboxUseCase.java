package com.personal.baton.application.watch.port.in;

import com.personal.baton.application.delivery.DispatchResult;

public interface DispatchWatchMonitorOutboxUseCase {

    DispatchResult dispatchPending();
}
