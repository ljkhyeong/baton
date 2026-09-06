package com.personal.baton.adapter.in.web.workspace;

import com.personal.baton.application.workspace.port.in.ResourceLinkPreviewUseCase;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ResourceLinkPreviewController {
    public static final String PATH = "/api/v1/teams/{teamId}/seasons/{seasonId}/resource-link-preview";
    private final ResourceLinkPreviewUseCase previews;

    public ResourceLinkPreviewController(ResourceLinkPreviewUseCase previews) {
        this.previews = previews;
    }

    @GetMapping(PATH)
    public ResponseEntity<ResourceLinkPreviewUseCase.Preview> preview(
            @PathVariable UUID teamId,
            @PathVariable UUID seasonId,
            @RequestHeader(name = WorkspaceLifecycleController.ACCESS_KEY_HEADER, required = false) String accessKey,
            @RequestParam @NotBlank @Size(max = 2048) String url) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(previews.preview(teamId, seasonId, accessKey, url));
    }
}
