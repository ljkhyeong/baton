package com.personal.baton.application.link;

import com.personal.baton.application.link.error.InvalidLinkIntentException;
import com.personal.baton.application.link.port.in.RoleResourceLinkUseCase;
import com.personal.baton.application.link.port.out.RoleResourceLinkPort;
import com.personal.baton.application.workspace.error.WorkspaceNotFoundException;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.RoleResourceResult;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.WorkspaceResult;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class RoleResourceLinkService implements RoleResourceLinkUseCase {

    static final String INVALID_IDEMPOTENCY_KEY = "INVALID_LINK_IDEMPOTENCY_KEY";
    static final String INVALID_EXPIRY = "INVALID_LINK_EXPIRY";
    private static final Duration MAXIMUM_LINK_LIFETIME = Duration.ofMinutes(15);

    private final WorkspaceUseCase workspaceUseCase;
    private final RoleResourceLinkPort roleResourceLinkPort;
    private final Clock clock;

    public RoleResourceLinkService(
            WorkspaceUseCase workspaceUseCase,
            RoleResourceLinkPort roleResourceLinkPort,
            Clock clock
    ) {
        this.workspaceUseCase = workspaceUseCase;
        this.roleResourceLinkPort = roleResourceLinkPort;
        this.clock = clock;
    }

    @Override
    public OpenRoleResourceLinkResult openRoleResourceLink(
            UUID teamId,
            UUID seasonId,
            UUID resourceId,
            String accessKey,
            String idempotencyKey,
            Instant expiresAt
    ) {
        WorkspaceResult workspace = workspaceUseCase.getWorkspace(teamId, seasonId, accessKey);
        RoleResourceResult resource = workspace.resources().stream()
                .filter(candidate -> candidate.id().equals(resourceId))
                .findFirst()
                .orElseThrow(() -> new WorkspaceNotFoundException(
                        "ROLE_RESOURCE_NOT_FOUND",
                        "자료를 찾을 수 없습니다"
                ));

        UUID parsedIdempotencyKey = requireCanonicalIdempotencyKey(idempotencyKey);
        requireValidExpiry(expiresAt);

        RoleResourceLinkPort.LinkNavigation navigation = roleResourceLinkPort.createNavigation(
                URI.create(resource.url()),
                parsedIdempotencyKey,
                expiresAt
        );
        RoutingMode routingMode = navigation.managedByBatonGo()
                ? RoutingMode.BATON_GO
                : RoutingMode.DIRECT;
        return new OpenRoleResourceLinkResult(
                navigation.navigationUrl(),
                routingMode,
                navigation.expiresAt()
        );
    }

    private UUID requireCanonicalIdempotencyKey(String value) {
        if (value == null) {
            throw invalidIdempotencyKey();
        }
        try {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equals(value)) {
                throw invalidIdempotencyKey();
            }
            return parsed;
        } catch (IllegalArgumentException exception) {
            throw invalidIdempotencyKey();
        }
    }

    private void requireValidExpiry(Instant expiresAt) {
        Instant now = clock.instant();
        if (expiresAt == null
                || !expiresAt.isAfter(now)
                || expiresAt.isAfter(now.plus(MAXIMUM_LINK_LIFETIME))) {
            throw new InvalidLinkIntentException(
                    INVALID_EXPIRY,
                    "링크 만료 시각은 현재보다 미래이고 15분 이내여야 합니다"
            );
        }
    }

    private InvalidLinkIntentException invalidIdempotencyKey() {
        return new InvalidLinkIntentException(
                INVALID_IDEMPOTENCY_KEY,
                "Idempotency-Key는 canonical UUID 형식이어야 합니다"
        );
    }
}
