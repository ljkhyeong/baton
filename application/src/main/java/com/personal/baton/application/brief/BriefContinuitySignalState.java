package com.personal.baton.application.brief;

import com.personal.baton.application.workspace.port.in.ContinuitySignalSeverity;
import com.personal.baton.application.workspace.port.in.ContinuitySignalType;
import java.util.UUID;

public record BriefContinuitySignalState(
        UUID signalId,
        ContinuitySignalType eventType,
        UUID subjectId,
        ContinuitySignalSeverity sourceSeverity,
        BriefContinuityEvent.State state,
        long latestRevision
) {
}
