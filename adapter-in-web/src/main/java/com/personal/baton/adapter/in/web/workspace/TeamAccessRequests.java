package com.personal.baton.adapter.in.web.workspace;

import com.personal.baton.domain.workspace.TeamPermission;
import java.util.UUID;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public final class TeamAccessRequests {
    private TeamAccessRequests() {}

    public record ActivateTeamAccessRequest(@NotNull UUID expectedAccountId, @NotNull UUID memberId) {}
    public record ExpectedAccountRequest(@NotNull UUID expectedAccountId) {}
    public record CreateTeamInvitationRequest(@NotNull UUID expectedAccountId, @NotNull UUID memberId, @NotNull TeamPermission permission) {}
    public record ChangeTeamPermissionRequest(@NotNull UUID expectedAccountId, TeamPermission permission) {}
    public record TeamInvitationTokenRequest(@NotNull UUID expectedAccountId, @NotNull @Pattern(regexp = "[A-Za-z0-9_-]{43}") String token) {
        @Override public String toString() { return "TeamInvitationTokenRequest[expectedAccountId=" + expectedAccountId + "]"; }
    }
}
