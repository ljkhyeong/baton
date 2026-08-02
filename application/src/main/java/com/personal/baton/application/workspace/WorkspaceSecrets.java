package com.personal.baton.application.workspace;

import java.util.Objects;

public record WorkspaceSecrets(String creationKey, String recoveryKey) {

    public WorkspaceSecrets {
        creationKey = Objects.requireNonNullElse(creationKey, "");
        recoveryKey = Objects.requireNonNullElse(recoveryKey, "");
    }

    public static WorkspaceSecrets unconfigured() {
        return new WorkspaceSecrets("", "");
    }

    @Override
    public String toString() {
        return "WorkspaceSecrets[creationKey=<redacted>, recoveryKey=<redacted>]";
    }
}
