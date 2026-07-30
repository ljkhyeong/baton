package com.personal.baton.adapter.in.web.identity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public final class IdentityRequests {

    private IdentityRequests() {
    }

    public record IssueBootstrapInvitationRequest(
            @NotNull UUID teamId,
            @NotNull UUID memberId
    ) {
    }

    public record AcceptInvitationRequest(
            @NotBlank
            @Size(max = 200)
            String token
    ) {
    }

    public record IssueMemberInvitationRequest(@NotNull UUID memberId) {
    }
}
