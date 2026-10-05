package com.personal.baton.application.calendar.port.in;

import com.personal.baton.application.delivery.DispatchResult;

public interface DispatchCalendarOutboxUseCase {

    DispatchResult dispatchPending();
}
