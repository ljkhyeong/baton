package com.personal.baton.bootstrap.config;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("baton.identity")
public record IdentityInvitationProperties(
        String bootstrapKey,
        String invitationHmacSecret,
        @DefaultValue("PT1H") Duration bootstrapInvitationTtl,
        @DefaultValue("PT24H") Duration memberInvitationTtl
) {

    public IdentityInvitationProperties {
        bootstrapKey = Objects.requireNonNullElse(bootstrapKey, "");
        invitationHmacSecret = Objects.requireNonNullElse(invitationHmacSecret, "");
    }

    @Override
    public String toString() {
        return "IdentityInvitationProperties[bootstrapKey=<redacted>, "
                + "invitationHmacSecret=<redacted>, "
                + "bootstrapInvitationTtl=" + bootstrapInvitationTtl + ", "
                + "memberInvitationTtl=" + memberInvitationTtl + "]";
    }
}
