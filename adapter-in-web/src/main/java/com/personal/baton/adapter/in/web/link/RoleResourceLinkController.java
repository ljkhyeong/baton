package com.personal.baton.adapter.in.web.link;

import com.personal.baton.application.link.port.in.RoleResourceLinkUseCase;
import com.personal.baton.application.link.port.in.RoleResourceLinkUseCase.OpenRoleResourceLinkResult;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/teams/{teamId}/seasons/{seasonId}/role-resources/{resourceId}/open-link")
public class RoleResourceLinkController {

    public static final String ACCESS_KEY_HEADER = "X-Baton-Access-Key";
    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final RoleResourceLinkUseCase useCase;

    public RoleResourceLinkController(RoleResourceLinkUseCase useCase) {
        this.useCase = useCase;
    }

    @PostMapping
    public ResponseEntity<RoleResourceLinkResponse> openRoleResourceLink(
            @PathVariable UUID teamId,
            @PathVariable UUID seasonId,
            @PathVariable UUID resourceId,
            @RequestHeader(name = ACCESS_KEY_HEADER, required = false) String accessKey,
            @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody RoleResourceLinkRequest request
    ) {
        OpenRoleResourceLinkResult result = useCase.openRoleResourceLink(
                teamId,
                seasonId,
                resourceId,
                accessKey,
                idempotencyKey,
                request.expiresAt()
        );
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(RoleResourceLinkResponse.from(result));
    }
}
