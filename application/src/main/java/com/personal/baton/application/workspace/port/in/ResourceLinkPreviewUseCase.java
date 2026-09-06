package com.personal.baton.application.workspace.port.in;

import java.util.UUID;

public interface ResourceLinkPreviewUseCase {
    Preview preview(UUID teamId, UUID seasonId, String accessKey, String url);

    record Preview(String title, String thumbnailUrl) {
        public static Preview unavailable() {
            return new Preview(null, null);
        }
    }
}
