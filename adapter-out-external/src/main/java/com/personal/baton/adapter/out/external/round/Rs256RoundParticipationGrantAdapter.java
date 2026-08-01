package com.personal.baton.adapter.out.external.round;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.personal.baton.adapter.out.external.http.TrustedHttpOrigin;
import com.personal.baton.application.round.error.RoundGrantOperationException;
import com.personal.baton.application.round.port.out.RoundParticipationGrantPort;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class Rs256RoundParticipationGrantAdapter implements RoundParticipationGrantPort {

    static final String RESOURCE_NOT_ELIGIBLE = "ROUND_RESOURCE_NOT_ELIGIBLE";
    static final String SIGNER_UNAVAILABLE = "ROUND_GRANT_SIGNER_UNAVAILABLE";
    private static final Duration MAXIMUM_TTL = Duration.ofMinutes(5);
    private static final Duration PUBLIC_KEY_RING_CACHE_TTL = Duration.ofSeconds(60);
    private static final int MAXIMUM_KEY_FILE_BYTES = 1024 * 1024;
    private static final Pattern CANONICAL_ROOM_PATH = Pattern.compile(
            "^/room/[abcdefghjkmnpqrstuvwxyz23456789]{4}"
                    + "(?:-[abcdefghjkmnpqrstuvwxyz23456789]{4}){2}$"
    );
    private static final Pattern KEY_ID = Pattern.compile("^[A-Za-z0-9._-]{1,128}$");
    private static final Pattern COMPACT_JWS = Pattern.compile(
            "^[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+$"
    );

    private final RoundParticipationGrantProperties properties;
    private final Clock clock;
    private volatile CachedPublicKeyRing cachedPublicKeyRing;

    public Rs256RoundParticipationGrantAdapter(
            RoundParticipationGrantProperties properties,
            Clock clock
    ) {
        this.properties = properties;
        this.clock = clock;
    }

    @PostConstruct
    void validateEnabledConfiguration() {
        if (!properties.isEnabled()) {
            return;
        }
        Settings settings = Settings.from(properties);
        loadSigningMaterial(settings);
    }

    @Override
    public SignedParticipationGrant issueParticipantGrant(
            ParticipantGrantCommand command
    ) {
        Objects.requireNonNull(command);
        try {
            Settings settings = Settings.from(properties);
            String roomId = requireCanonicalRoundRoom(
                    command.resourceUrl(),
                    settings.roundPublicOrigin()
            );
            SigningMaterial material = loadSigningMaterial(settings);
            Instant issuedAt = command.issuedAt();
            Instant expiresAt = issuedAt.plus(settings.ttl());
            String tokenId = UUID.randomUUID().toString();
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer(settings.issuer().uri().toString())
                    .audience(settings.audience())
                    .subject(command.accountId().toString())
                    .issueTime(Date.from(issuedAt))
                    .expirationTime(Date.from(expiresAt))
                    .jwtID(tokenId)
                    .claim("room_id", roomId)
                    .claim("study_id", command.seasonId().toString())
                    .claim("role", "participant")
                    .build();
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256)
                            .type(JOSEObjectType.JWT)
                            .keyID(settings.activeKid())
                            .build(),
                    claims
            );
            jwt.sign(new RSASSASigner(material.privateKey()));
            String token = jwt.serialize();
            if (!COMPACT_JWS.matcher(token).matches()) {
                throw signerUnavailable();
            }
            return new SignedParticipationGrant(token, roomId, issuedAt, expiresAt);
        } catch (RoundGrantOperationException exception) {
            throw exception;
        } catch (JOSEException | RuntimeException exception) {
            throw signerUnavailable();
        }
    }

    @Override
    public String canonicalResourceUrl(String roomId) {
        try {
            Settings settings = Settings.from(properties);
            if (roomId == null
                    || !CANONICAL_ROOM_PATH.matcher("/room/" + roomId).matches()) {
                throw grantForbidden();
            }
            return settings.roundPublicOrigin()
                    .withPath("/room/" + roomId)
                    .toString();
        } catch (RoundGrantOperationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw signerUnavailable();
        }
    }

    @Override
    public PublicJwkSet loadPublicJwkSet() {
        try {
            Settings settings = Settings.from(properties);
            return loadPublicKeyRing(settings).publicJwkSet();
        } catch (RoundGrantOperationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw signerUnavailable();
        }
    }

    private SigningMaterial loadSigningMaterial(Settings settings) {
        PublicKeyRing keyRing = loadPublicKeyRing(settings);
        RSAKey activePublicKey = keyRing.keys().stream()
                .filter(key -> settings.activeKid().equals(key.getKeyID()))
                .findFirst()
                .orElseThrow(this::signerUnavailable);
        RSAPrivateCrtKey privateKey = readPrivateKey(settings.privateKeyPath());
        if (privateKey.getModulus().bitLength() < 2048
                || !privateKey.getModulus().equals(activePublicKey.getModulus().decodeToBigInteger())
                || !privateKey.getPublicExponent().equals(
                        activePublicKey.getPublicExponent().decodeToBigInteger()
                )) {
            throw signerUnavailable();
        }
        return new SigningMaterial(privateKey);
    }

    private PublicKeyRing loadPublicKeyRing(Settings settings) {
        Instant now = clock.instant();
        CachedPublicKeyRing cached = cachedPublicKeyRing;
        if (cached != null && cached.isReusable(settings.jwkSetPath(), now)) {
            return cached.keyRing();
        }
        synchronized (this) {
            cached = cachedPublicKeyRing;
            if (cached != null && cached.isReusable(settings.jwkSetPath(), now)) {
                return cached.keyRing();
            }
            PublicKeyRing loaded = parsePublicKeyRing(settings.jwkSetPath());
            cachedPublicKeyRing = new CachedPublicKeyRing(
                    settings.jwkSetPath(),
                    loaded,
                    now.plus(PUBLIC_KEY_RING_CACHE_TTL)
            );
            return loaded;
        }
    }

    private PublicKeyRing parsePublicKeyRing(Path jwkSetPath) {
        String source = readBoundedFile(jwkSetPath);
        try {
            JWKSet parsed = JWKSet.parse(source);
            List<RSAKey> keys = new ArrayList<>();
            Set<String> keyIds = new HashSet<>();
            for (JWK key : parsed.getKeys()) {
                if (!(key instanceof RSAKey rsaKey)
                        || rsaKey.isPrivate()
                        || rsaKey.size() < 2048
                        || rsaKey.getKeyID() == null
                        || !KEY_ID.matcher(rsaKey.getKeyID()).matches()
                        || !keyIds.add(rsaKey.getKeyID())
                        || !JWSAlgorithm.RS256.equals(rsaKey.getAlgorithm())
                        || !KeyUse.SIGNATURE.equals(rsaKey.getKeyUse())) {
                    throw signerUnavailable();
                }
                keys.add(new RSAKey.Builder(
                        rsaKey.getModulus(),
                        rsaKey.getPublicExponent()
                )
                        .keyID(rsaKey.getKeyID())
                        .algorithm(JWSAlgorithm.RS256)
                        .keyUse(KeyUse.SIGNATURE)
                        .build());
            }
            if (keys.isEmpty()) {
                throw signerUnavailable();
            }
            keys.sort(Comparator.comparing(RSAKey::getKeyID));
            String body = new JWKSet(new ArrayList<>(keys)).toString();
            if (body.contains("\"d\"")
                    || body.contains("\"p\"")
                    || body.contains("\"q\"")
                    || body.contains("\"dp\"")
                    || body.contains("\"dq\"")
                    || body.contains("\"qi\"")) {
                throw signerUnavailable();
            }
            return new PublicKeyRing(
                    List.copyOf(keys),
                    new PublicJwkSet(body, etag(body))
            );
        } catch (ParseException exception) {
            throw signerUnavailable();
        }
    }

    private RSAPrivateCrtKey readPrivateKey(Path path) {
        String pem = readBoundedFile(path);
        if (!pem.startsWith("-----BEGIN PRIVATE KEY-----")
                || !pem.endsWith("-----END PRIVATE KEY-----")
                || pem.contains("-----BEGIN RSA PRIVATE KEY-----")) {
            throw signerUnavailable();
        }
        String encoded = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        try {
            byte[] der = Base64.getDecoder().decode(encoded);
            var key = KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(der));
            if (!(key instanceof RSAPrivateCrtKey rsaKey)) {
                throw signerUnavailable();
            }
            return rsaKey;
        } catch (IllegalArgumentException | GeneralSecurityException exception) {
            throw signerUnavailable();
        }
    }

    private String requireCanonicalRoundRoom(
            String value,
            TrustedHttpOrigin expectedOrigin
    ) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException exception) {
            throw resourceNotEligible();
        }
        String rawPath = uri.getRawPath();
        if (!uri.isAbsolute()
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null
                || !expectedOrigin.hasSameOriginAs(uri)
                || rawPath == null
                || !CANONICAL_ROOM_PATH.matcher(rawPath).matches()) {
            throw resourceNotEligible();
        }
        return rawPath.substring("/room/".length());
    }

    private String readBoundedFile(Path path) {
        try {
            long size = Files.size(path);
            if (size <= 0 || size > MAXIMUM_KEY_FILE_BYTES || !Files.isRegularFile(path)) {
                throw signerUnavailable();
            }
            return Files.readString(path, StandardCharsets.UTF_8).trim();
        } catch (IOException | SecurityException exception) {
            throw signerUnavailable();
        }
    }

    private String etag(String body) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(body.getBytes(StandardCharsets.UTF_8));
            return "sha256-" + Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (GeneralSecurityException exception) {
            throw signerUnavailable();
        }
    }

    private RoundGrantOperationException resourceNotEligible() {
        return new RoundGrantOperationException(
                RESOURCE_NOT_ELIGIBLE,
                "역할 자료가 canonical ROUND room을 가리키지 않습니다"
        );
    }

    private RoundGrantOperationException grantForbidden() {
        return new RoundGrantOperationException(
                "ROUND_GRANT_FORBIDDEN",
                "현재 계정으로 이 ROUND room에 참여할 수 없습니다"
        );
    }

    private RoundGrantOperationException signerUnavailable() {
        return new RoundGrantOperationException(
                SIGNER_UNAVAILABLE,
                "ROUND 참여권 서명기를 사용할 수 없습니다"
        );
    }

    private record Settings(
            TrustedHttpOrigin issuer,
            String audience,
            Duration ttl,
            String activeKid,
            Path privateKeyPath,
            Path jwkSetPath,
            TrustedHttpOrigin roundPublicOrigin
    ) {

        private static Settings from(RoundParticipationGrantProperties properties) {
            if (!properties.isEnabled()) {
                throw unavailable();
            }
            TrustedHttpOrigin issuer;
            TrustedHttpOrigin roundPublicOrigin;
            try {
                issuer = TrustedHttpOrigin.from(properties.getIssuer());
                roundPublicOrigin = TrustedHttpOrigin.from(properties.getRoundPublicOrigin());
            } catch (IllegalArgumentException exception) {
                throw unavailable();
            }
            String audience = normalized(properties.getAudience());
            String cookieName = normalized(properties.getCookieName());
            String activeKid = normalized(properties.getActiveKid());
            Duration ttl = properties.getTtl();
            Path privateKeyPath = requireAbsolutePath(properties.getPrivateKeyPath());
            Path jwkSetPath = requireAbsolutePath(properties.getJwkSetPath());
            if (!"round".equals(audience)
                    || !"__Secure-round_access".equals(cookieName)
                    || ttl == null
                    || ttl.isZero()
                    || ttl.isNegative()
                    || ttl.compareTo(MAXIMUM_TTL) > 0
                    || activeKid == null
                    || !KEY_ID.matcher(activeKid).matches()
                    || issuer.port() == 0
                    || roundPublicOrigin.port() == 0
                    || !isSecureBrowserOrigin(issuer)
                    || !isSecureBrowserOrigin(roundPublicOrigin)) {
                throw unavailable();
            }
            return new Settings(
                    issuer,
                    audience,
                    ttl,
                    activeKid,
                    privateKeyPath,
                    jwkSetPath,
                    roundPublicOrigin
            );
        }

        private static String normalized(String value) {
            return value == null ? null : value.trim();
        }

        private static Path requireAbsolutePath(String value) {
            String normalized = normalized(value);
            if (normalized == null || normalized.isBlank()) {
                throw unavailable();
            }
            try {
                Path path = Path.of(normalized).normalize();
                if (!path.isAbsolute()) {
                    throw unavailable();
                }
                return path;
            } catch (RuntimeException exception) {
                throw unavailable();
            }
        }

        private static boolean isSecureBrowserOrigin(TrustedHttpOrigin origin) {
            if ("https".equals(origin.scheme())) {
                return true;
            }
            String host = origin.host();
            return "http".equals(origin.scheme())
                    && ("localhost".equals(host)
                    || "127.0.0.1".equals(host)
                    || "::1".equals(host));
        }

        private static RoundGrantOperationException unavailable() {
            return new RoundGrantOperationException(
                    SIGNER_UNAVAILABLE,
                    "ROUND 참여권 서명기를 사용할 수 없습니다"
            );
        }
    }

    private record PublicKeyRing(List<RSAKey> keys, PublicJwkSet publicJwkSet) {
    }

    private record CachedPublicKeyRing(
            Path path,
            PublicKeyRing keyRing,
            Instant refreshAt
    ) {

        private boolean isReusable(Path expectedPath, Instant now) {
            return path.equals(expectedPath) && now.isBefore(refreshAt);
        }
    }

    private record SigningMaterial(RSAPrivateCrtKey privateKey) {

        @Override
        public String toString() {
            return "SigningMaterial[redacted]";
        }
    }
}
