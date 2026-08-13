package com.personal.baton.bootstrap.config;

import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("baton.workspace")
public record WorkspaceSecretProperties(
        @Pattern(
                regexp = "(?:|[A-Za-z0-9._~-]{32,200})",
                message = "32~200자의 URL-safe ASCII 문자이거나 비어 있어야 합니다"
        )
        @DefaultValue("") String creationKey,
        @Pattern(
                regexp = "(?:|[A-Za-z0-9._~-]{32,200})",
                message = "32~200자의 URL-safe ASCII 문자이거나 비어 있어야 합니다"
        )
        @DefaultValue("") String recoveryKey
) {

    @Override
    public String toString() {
        return "WorkspaceSecretProperties[creationKey=<redacted>, recoveryKey=<redacted>]";
    }
}
