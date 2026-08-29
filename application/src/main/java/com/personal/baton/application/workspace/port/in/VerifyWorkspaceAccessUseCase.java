package com.personal.baton.application.workspace.port.in;

import com.personal.baton.domain.workspace.Season;
import java.util.UUID;

public interface VerifyWorkspaceAccessUseCase {

    void verifyTeamRead(UUID teamId, String accessKey);

    Season verifyRead(UUID teamId, UUID seasonId, String accessKey);

    Season verifyMutation(UUID teamId, UUID seasonId, String accessKey);
}
