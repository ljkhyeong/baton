package com.personal.baton.adapter.out.external.link;

import com.personal.baton.application.link.error.InvalidLinkIntentException;
import com.personal.baton.application.link.error.LinkGatewayConflictException;
import com.personal.baton.application.link.error.LinkGatewayUnavailableException;
import com.personal.baton.application.link.port.out.RoleResourceLinkPort;
import java.net.URI;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

public class BatonGoRoleResourceLinkAdapter implements RoleResourceLinkPort {

    private static final Pattern CANONICAL_ROUND_ROOM_PATH = Pattern.compile(
            "^/room/[abcdefghjkmnpqrstuvwxyz23456789]{4}"
                    + "(?:-[abcdefghjkmnpqrstuvwxyz23456789]{4}){2}$"
    );
    private static final Pattern CANONICAL_BATON_GO_SHORT_PATH = Pattern.compile(
            "^/l/[A-Za-z0-9_-]{22}$"
    );

    private final BatonGoSettings settings;
    private final RestClient restClient;

    public BatonGoRoleResourceLinkAdapter(
            BatonGoSettings settings,
            RestClient restClient
    ) {
        this.settings = Objects.requireNonNull(settings, "BATON GO 설정은 필수입니다");
        this.restClient = Objects.requireNonNull(restClient, "BATON GO RestClient는 필수입니다");
    }

    @Override
    public LinkNavigation createNavigation(
            URI resourceUrl,
            UUID idempotencyKey,
            Instant expiresAt
    ) {
        Objects.requireNonNull(resourceUrl, "자료 URL은 필수입니다");
        Objects.requireNonNull(idempotencyKey, "멱등 키는 필수입니다");
        Objects.requireNonNull(expiresAt, "만료 시각은 필수입니다");

        if (!settings.enabled()) {
            return new LinkNavigation(resourceUrl, false, null);
        }
        if (!sameOrigin(resourceUrl, settings.roundPublicOrigin())) {
            return new LinkNavigation(resourceUrl, false, null);
        }
        if (!isTrustedRoundRoom(resourceUrl)) {
            throw new InvalidLinkIntentException(
                    "INVALID_ROUND_RESOURCE_URL",
                    "ROUND 자료 링크는 query와 fragment가 없는 canonical room URL이어야 합니다"
            );
        }

        GoLinkRequest request = new GoLinkRequest(
                "ROUND",
                resourceUrl.getRawPath(),
                "MEETING_ENTRY",
                null,
                expiresAt
        );
        GoLinkResponse response = createBatonGoLink(request, idempotencyKey);
        URI shortUrl = requireSafeShortUrl(response, request);
        return new LinkNavigation(shortUrl, true, expiresAt);
    }

    private GoLinkResponse createBatonGoLink(GoLinkRequest request, UUID idempotencyKey) {
        try {
            ResponseEntity<GoLinkResponse> response = restClient.post()
                    .uri(settings.createLinkEndpoint())
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> {
                        headers.setBearerAuth(settings.managementToken());
                        headers.set("Idempotency-Key", idempotencyKey.toString());
                    })
                    .body(request)
                    .retrieve()
                    .onStatus(
                            status -> status.value() == HttpStatus.CONFLICT.value(),
                            (ignoredRequest, ignoredResponse) -> {
                                throw new LinkGatewayConflictException();
                            }
                    )
                    .onStatus(
                            status -> status.isError(),
                            (ignoredRequest, ignoredResponse) -> {
                                throw new LinkGatewayUnavailableException();
                            }
                    )
                    .toEntity(GoLinkResponse.class);
            if (response.getStatusCode().value() != HttpStatus.OK.value()
                    && response.getStatusCode().value() != HttpStatus.CREATED.value()) {
                throw new LinkGatewayUnavailableException();
            }
            return response.getBody();
        } catch (LinkGatewayConflictException | LinkGatewayUnavailableException exception) {
            throw exception;
        } catch (RestClientException | IllegalArgumentException exception) {
            throw new LinkGatewayUnavailableException(exception);
        }
    }

    private URI requireSafeShortUrl(GoLinkResponse response, GoLinkRequest request) {
        if (response == null || response.shortUrl() == null) {
            throw new LinkGatewayUnavailableException();
        }
        if (!Objects.equals(response.targetSystem(), request.targetSystem())
                || !Objects.equals(response.targetPath(), request.targetPath())
                || !Objects.equals(response.purpose(), request.purpose())
                || !Objects.equals(response.notBefore(), request.notBefore())
                || !Objects.equals(response.expiresAt(), request.expiresAt())
                || response.revokedAt() != null) {
            throw new LinkGatewayUnavailableException();
        }
        URI shortUrl = response.shortUrl();
        String scheme = normalizedScheme(shortUrl);
        if (!shortUrl.isAbsolute()
                || !(scheme.equals("http") || scheme.equals("https"))
                || shortUrl.getHost() == null
                || shortUrl.getHost().isBlank()
                || shortUrl.getUserInfo() != null
                || shortUrl.getQuery() != null
                || shortUrl.getFragment() != null
                || !sameOrigin(shortUrl, settings.publicOrigin())
                || !CANONICAL_BATON_GO_SHORT_PATH.matcher(shortUrl.getRawPath()).matches()) {
            throw new LinkGatewayUnavailableException();
        }
        return shortUrl;
    }

    private boolean isTrustedRoundRoom(URI resourceUrl) {
        return resourceUrl.isAbsolute()
                && resourceUrl.getUserInfo() == null
                && resourceUrl.getQuery() == null
                && resourceUrl.getFragment() == null
                && sameOrigin(resourceUrl, settings.roundPublicOrigin())
                && CANONICAL_ROUND_ROOM_PATH.matcher(resourceUrl.getRawPath()).matches();
    }

    private static boolean sameOrigin(URI left, URI right) {
        return normalizedScheme(left).equals(normalizedScheme(right))
                && normalizedHost(left).equals(normalizedHost(right))
                && effectivePort(left) == effectivePort(right);
    }

    private static String normalizedScheme(URI uri) {
        return uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    }

    private static String normalizedHost(URI uri) {
        return uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return switch (normalizedScheme(uri)) {
            case "http" -> 80;
            case "https" -> 443;
            default -> -1;
        };
    }

    private record GoLinkRequest(
            String targetSystem,
            String targetPath,
            String purpose,
            Instant notBefore,
            Instant expiresAt
    ) {
    }

    private record GoLinkResponse(
            URI shortUrl,
            String targetSystem,
            String targetPath,
            String purpose,
            Instant notBefore,
            Instant expiresAt,
            Instant revokedAt
    ) {
    }
}
