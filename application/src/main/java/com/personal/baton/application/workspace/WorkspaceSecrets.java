package com.personal.baton.application.workspace;

public record WorkspaceSecrets(String creationKey, String recoveryKey) {

    @Override
    public String toString() {
        return "WorkspaceSecrets[creationKey=<redacted>, recoveryKey=<redacted>]";
    }
}
