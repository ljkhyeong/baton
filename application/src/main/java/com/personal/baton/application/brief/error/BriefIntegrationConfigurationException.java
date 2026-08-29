package com.personal.baton.application.brief.error;

public class BriefIntegrationConfigurationException extends RuntimeException {

    public BriefIntegrationConfigurationException() {
        super("BRIEF 연동 설정을 확인해야 합니다");
    }
}

