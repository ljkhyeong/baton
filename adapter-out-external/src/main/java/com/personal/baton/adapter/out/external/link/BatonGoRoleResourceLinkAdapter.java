package com.personal.baton.adapter.out.external.link;

import com.personal.baton.application.link.error.InvalidLinkIntentException;
import com.personal.baton.application.link.error.LinkGatewayConflictException;
import com.personal.baton.application.link.error.LinkGatewayUnavailableException;
import com.personal.baton.application.link.port.out.RoleResourceLinkPort;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.http.client.HttpRedirects;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class BatonGoRoleResourceLinkAdapter implements RoleResourceLinkPort {

    private static final Pattern CANONICAL_ROUND_ROOM_PATH = Pattern.compile(
            "^/room/[abcdefghjkmnpqrstuvwxyz23456789]{4}"
                    + "(?:-[abcdefghjkmnpqrstuvwxyz23456789]{4}){2}$"
    );
    private static final Pattern CANONICAL_BATON_GO_SHORT_PATH = Pattern.compile(
            "^/l/[A-Za-z0-9_-]{22}$"
    );

    private final Settings settings;
    private final RestClient restClient;

    @Autowired
    public BatonGoRoleResourceLinkAdapter(
            BatonGoProperties properties,
            RestClient.Builder restClientBuilder,
            ClientHttpRequestFactoryBuilder<?> requestFactoryBuilder,
            HttpClientSettings httpClientSettings
    ) {
        this(properties, createRestClient(
                properties,
                restClientBuilder,
                requestFactoryBuilder,
                httpClientSettings
        ));
    }

    BatonGoRoleResourceLinkAdapter(BatonGoProperties properties, RestClient restClient) {
        this.settings = Settings.from(properties);
        this.restClient = restClient;
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

    private static RestClient createRestClient(
            BatonGoProperties properties,
            RestClient.Builder restClientBuilder,
            ClientHttpRequestFactoryBuilder<?> requestFactoryBuilder,
            HttpClientSettings httpClientSettings
    ) {
        Objects.requireNonNull(restClientBuilder, "RestClient.Builder는 필수입니다");
        Objects.requireNonNull(
                requestFactoryBuilder,
                "ClientHttpRequestFactoryBuilder는 필수입니다"
        );
        Objects.requireNonNull(httpClientSettings, "HttpClientSettings는 필수입니다");
        Settings settings = Settings.from(properties);
        if (!settings.enabled()) {
            return restClientBuilder.build();
        }
        HttpClientSettings clientSettings = httpClientSettings
                .withTimeouts(settings.connectTimeout(), settings.readTimeout())
                .withRedirects(HttpRedirects.DONT_FOLLOW);
        return restClientBuilder
                .requestFactory(requestFactoryBuilder.build(clientSettings))
                .build();
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

    private static URI requireHttpOrigin(URI uri, String name) {
        String scheme = uri == null ? "" : normalizedScheme(uri);
        String path = uri == null ? null : uri.getRawPath();
        if (uri == null
                || !uri.isAbsolute()
                || !(scheme.equals("http") || scheme.equals("https"))
                || uri.getHost() == null
                || uri.getHost().isBlank()
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null
                || (path != null && !path.isEmpty() && !path.equals("/"))) {
            throw new LinkGatewayUnavailableException(
                    new IllegalStateException(name + " 설정은 http(s) origin이어야 합니다")
            );
        }
        try {
            return new URI(
                    scheme,
                    null,
                    normalizedHost(uri),
                    uri.getPort(),
                    null,
                    null,
                    null
            );
        } catch (URISyntaxException exception) {
            throw new LinkGatewayUnavailableException(exception);
        }
    }

    private static URI createLinkEndpoint(URI baseOrigin) {
        try {
            return new URI(
                    normalizedScheme(baseOrigin),
                    null,
                    normalizedHost(baseOrigin),
                    baseOrigin.getPort(),
                    "/api/v1/links",
                    null,
                    null
            );
        } catch (URISyntaxException exception) {
            throw new LinkGatewayUnavailableException(exception);
        }
    }

    private record Settings(
            boolean enabled,
            URI createLinkEndpoint,
            URI publicOrigin,
            String managementToken,
            URI roundPublicOrigin,
            Duration connectTimeout,
            Duration readTimeout
    ) {

        private static Settings from(BatonGoProperties properties) {
            Objects.requireNonNull(properties, "BATON GO 설정은 필수입니다");
            if (!properties.isEnabled()) {
                return new Settings(false, null, null, null, null, null, null);
            }
            URI baseOrigin = requireHttpOrigin(properties.getBaseUrl(), "base-url");
            URI publicOrigin = requireHttpOrigin(
                    properties.getPublicBaseUrl(),
                    "public-base-url"
            );
            URI roundOrigin = requireHttpOrigin(
                    properties.getRoundPublicBaseUrl(),
                    "round-public-base-url"
            );
            String managementToken = properties.getManagementToken();
            Duration connectTimeout = properties.getConnectTimeout();
            Duration readTimeout = properties.getReadTimeout();
            if (managementToken == null || managementToken.length() < 32) {
                throw new LinkGatewayUnavailableException(
                        new IllegalStateException("management-token은 32자 이상이어야 합니다")
                );
            }
            if (connectTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative()) {
                throw new LinkGatewayUnavailableException(
                        new IllegalStateException("connect-timeout은 양수여야 합니다")
                );
            }
            if (readTimeout == null || readTimeout.isZero() || readTimeout.isNegative()) {
                throw new LinkGatewayUnavailableException(
                        new IllegalStateException("read-timeout은 양수여야 합니다")
                );
            }
            return new Settings(
                    true,
                    BatonGoRoleResourceLinkAdapter.createLinkEndpoint(baseOrigin),
                    publicOrigin,
                    managementToken,
                    roundOrigin,
                    connectTimeout,
                    readTimeout
            );
        }
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
