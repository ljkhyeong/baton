package com.personal.baton.application.identity;

import java.time.Duration;
import java.util.Objects;

public record IdentityInvitationSettings(
        String bootstrapKey,
        String invitationHmacSecret,
        Duration bootstrapInvitationTtl,
        Duration memberInvitationTtl
) {

    public IdentityInvitationSettings {
        bootstrapKey = Objects.requireNonNullElse(bootstrapKey, "");
        invitationHmacSecret = Objects.requireNonNullElse(invitationHmacSecret, "");
    }

    @Override
    public String toString() {
        return "IdentityInvitationSettings[bootstrapKey=<redacted>, "
                + "invitationHmacSecret=<redacted>, "
                + "bootstrapInvitationTtl=" + bootstrapInvitationTtl + ", "
                + "memberInvitationTtl=" + memberInvitationTtl + "]";
    }
}
