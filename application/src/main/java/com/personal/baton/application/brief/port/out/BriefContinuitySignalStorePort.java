package com.personal.baton.application.brief.port.out;

import com.personal.baton.application.brief.BriefContinuitySignalState;
import com.personal.baton.application.brief.BriefContinuitySignalScope;
import com.personal.baton.application.brief.BriefContinuityEvent;
import java.util.List;
import java.util.UUID;

public interface BriefContinuitySignalStorePort {

    void lockSeason(UUID teamId, UUID seasonId);

    List<BriefContinuitySignalScope> findReconciliationScopes();

    List<BriefContinuitySignalState> findBySeason(UUID teamId, UUID seasonId);

    void append(UUID subjectId, BriefContinuityEvent event);
}
