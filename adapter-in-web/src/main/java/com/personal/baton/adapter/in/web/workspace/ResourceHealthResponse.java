package com.personal.baton.adapter.in.web.workspace;

import com.personal.baton.application.workspace.port.in.InspectResourceHealthUseCase.Result;
import java.time.Instant;
import java.util.UUID;

public record ResourceHealthResponse(UUID resourceId, String health, String availability,
                                     Instant lastCheckedAt, boolean checkRequestAllowed) {
    static ResourceHealthResponse from(Result result) {
        return new ResourceHealthResponse(result.resourceId(), result.health().name(),
                result.availability().name(), result.lastCheckedAt(), result.checkRequestAllowed());
    }
}
