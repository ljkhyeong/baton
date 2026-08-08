package com.personal.baton.application.workspace.port.in;

import java.util.UUID;

public interface VerifyWorkspaceAccessUseCase {

    void verifyTeamRead(UUID teamId, String accessKey);

    void verifyMutation(UUID teamId, UUID seasonId, String accessKey);
}
