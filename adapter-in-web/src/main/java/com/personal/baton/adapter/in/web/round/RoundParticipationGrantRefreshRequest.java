package com.personal.baton.adapter.in.web.round;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record RoundParticipationGrantRefreshRequest(
        @NotNull UUID teamId,
        @NotNull UUID seasonId,
        @NotNull UUID resourceId
) {

    @JsonAnySetter
    public void rejectUnknownProperty(String name, Object value) {
        throw new IllegalArgumentException(
                "ROUND 참여권 갱신 locator에 알 수 없는 필드가 있습니다"
        );
    }
}
