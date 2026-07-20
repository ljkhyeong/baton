package com.personal.baton.application.workspace.error;

public class WorkspaceNotFoundException extends RuntimeException {

    private final String code;

    public WorkspaceNotFoundException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
