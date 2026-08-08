package com.personal.baton.adapter.in.web.roundauth;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public final class RoundAdministrationRequests {

    private RoundAdministrationRequests() {
    }

    public record MembershipClaimRequest(
            @NotNull UUID teamId,
            @NotNull UUID seasonId,
            @NotNull UUID memberId
    ) {
    }

    public record CreateRoomMappingRequest(
            @NotNull UUID teamId,
            @NotNull UUID seasonId,
            @NotNull UUID resourceId
    ) {
    }
}
