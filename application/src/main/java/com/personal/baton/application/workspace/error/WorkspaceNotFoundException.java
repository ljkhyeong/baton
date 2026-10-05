package com.personal.baton.application.workspace.error;

public class WorkspaceNotFoundException extends RuntimeException {

    private final String code;

    public WorkspaceNotFoundException(String code, String message) {
        super(message);
        this.code = code;
    }

    public static WorkspaceNotFoundException team() {
        return new WorkspaceNotFoundException("TEAM_NOT_FOUND", "팀을 찾을 수 없습니다");
    }

    public static WorkspaceNotFoundException season() {
        return new WorkspaceNotFoundException("SEASON_NOT_FOUND", "시즌을 찾을 수 없습니다");
    }

    public static WorkspaceNotFoundException role() {
        return new WorkspaceNotFoundException("ROLE_NOT_FOUND", "역할을 찾을 수 없습니다");
    }

    public static WorkspaceNotFoundException roleResource() {
        return new WorkspaceNotFoundException("ROLE_RESOURCE_NOT_FOUND", "자료를 찾을 수 없습니다");
    }

    public static WorkspaceNotFoundException decision() {
        return new WorkspaceNotFoundException("DECISION_NOT_FOUND", "결정 기록을 찾을 수 없습니다");
    }

    public String getCode() {
        return code;
    }
}
