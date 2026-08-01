package com.personal.baton.application.workspace.port.in;

import java.util.UUID;

public interface WorkspaceAccessKeyUseCase {

    AccessKeyResult rotateAccessKey(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            String currentAccessKey
    );

    AccessKeyResult recoverAccessKey(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            String recoveryKey
    );

    record AccessKeyResult(String accessKey) {
    }
}
