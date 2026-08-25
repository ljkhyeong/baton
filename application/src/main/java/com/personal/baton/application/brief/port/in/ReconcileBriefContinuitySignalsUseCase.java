package com.personal.baton.application.brief.port.in;

import java.util.UUID;

public interface ReconcileBriefContinuitySignalsUseCase {

    int reconcile(UUID teamId, UUID seasonId);
}
