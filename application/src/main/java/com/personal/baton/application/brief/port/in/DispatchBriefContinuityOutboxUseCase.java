package com.personal.baton.application.brief.port.in;

import com.personal.baton.application.delivery.DispatchResult;

public interface DispatchBriefContinuityOutboxUseCase {

    DispatchResult dispatchPending();
}
