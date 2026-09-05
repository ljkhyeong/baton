package com.personal.baton.adapter.in.web.workspace;

import com.personal.baton.application.workspace.port.in.InspectResourceHealthUseCase.CheckResult;
import java.util.UUID;

public record ResourceCheckResponse(UUID resourceId, String status) {
    static ResourceCheckResponse from(CheckResult result) {
        return new ResourceCheckResponse(result.resourceId(), result.status().name());
    }
}
