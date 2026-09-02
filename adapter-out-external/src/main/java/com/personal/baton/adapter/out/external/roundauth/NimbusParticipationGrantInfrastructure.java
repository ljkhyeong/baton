package com.personal.baton.adapter.out.external.roundauth;

import com.personal.baton.adapter.out.external.http.ExternalHttpOrigin;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import com.personal.baton.application.roundauth.error.ParticipationGrantUnavailableException;
import com.personal.baton.application.roundauth.port.out.ParticipationGrantJwkSetProvider;
import com.personal.baton.application.roundauth.port.out.ParticipationGrantSigner;
import com.personal.baton.domain.roundauth.RoundRoomId;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtEncodingException;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

public final class NimbusParticipationGrantInfrastructure
        implements ParticipationGrantSigner, ParticipationGrantJwkSetProvider {

    private static final Pattern KID_PATTERN = Pattern.compile(
            "[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"
    );
    private static final int MINIMUM_RSA_BITS = 2048;
    private static final long GRANT_LIFETIME_SECONDS = 300;

    private final String issuer;
    private final String audience;
    private final String currentKid;
    private final JwtEncoder jwtEncoder;
    private final String publicJwkSetJson;

    public NimbusParticipationGrantInfrastructure(
            String issuer,
            String audience,
            SigningKeyMaterial currentKey,
            List<PublicKeyMaterial> previousPublicKeys
    ) {
        this.issuer = requireSecureIssuer(issuer);
        this.audience = requireText(audience, "ROUND 참여권 audience는 필수입니다");
        if (currentKey == null) {
            throw new IllegalStateException("현재 ROUND 서명 키는 필수입니다");
        }
        this.currentKid = requireKid(currentKey.kid());

        RSAPublicKey currentPublicKey = readPublicKey(currentKey.publicKeyPath());
        RSAPrivateKey currentPrivateKey = readPrivateKey(currentKey.privateKeyPath());
        requireStrongKey(currentPublicKey, this.currentKid);
        requireMatchingKeyPair(currentPublicKey, currentPrivateKey);

        RSAKey currentJwk = rsaJwk(currentKid, currentPublicKey, currentPrivateKey);
        this.jwtEncoder = new NimbusJwtEncoder(
                new ImmutableJWKSet<SecurityContext>(new JWKSet(currentJwk))
        );

        List<JWK> publicKeys = new ArrayList<>();
        publicKeys.add(currentJwk.toPublicJWK());
        Set<String> kids = new HashSet<>();
        kids.add(currentKid);
        for (PublicKeyMaterial previous : previousPublicKeys) {
            String previousKid = requireKid(previous.kid());
            if (!kids.add(previousKid)) {
                throw new IllegalStateException("ROUND JWK kid는 중복될 수 없습니다: " + previousKid);
            }
            RSAPublicKey previousPublicKey = readPublicKey(previous.publicKeyPath());
            requireStrongKey(previousPublicKey, previousKid);
            publicKeys.add(rsaJwk(previousKid, previousPublicKey, null));
        }
        this.publicJwkSetJson = new JWKSet(publicKeys).toString();
    }

    @Override
    public String sign(ParticipationGrantClaims claims) {
        requireClaims(claims);
        JwsHeader headers = JwsHeader.with(SignatureAlgorithm.RS256)
                .keyId(currentKid)
                .type("JWT")
                .build();
        JwtClaimsSet jwtClaims = JwtClaimsSet.builder()
                .issuer(issuer)
                .audience(List.of(audience))
                .subject(claims.accountId().toString())
                .issuedAt(claims.issuedAt())
                .expiresAt(claims.expiresAt())
                .id(claims.tokenId().toString())
                .claim("study_id", claims.teamId().toString())
                .claim("room_id", claims.roomId())
                .claim("role", claims.role())
                .build();
        try {
            return jwtEncoder.encode(JwtEncoderParameters.from(headers, jwtClaims))
                    .getTokenValue();
        } catch (JwtEncodingException exception) {
            throw new ParticipationGrantUnavailableException(
                    "ROUND 참여권에 서명하지 못했습니다",
                    exception
            );
        }
    }

    @Override
    public String readPublicJwkSetJson() {
        return publicJwkSetJson;
    }

    private static void requireClaims(ParticipationGrantClaims claims) {
        Objects.requireNonNull(claims, "ROUND 참여권 claim은 필수입니다");
        Objects.requireNonNull(claims.accountId(), "ROUND 참여권 accountId는 필수입니다");
        Objects.requireNonNull(claims.teamId(), "ROUND 참여권 teamId는 필수입니다");
        Objects.requireNonNull(claims.tokenId(), "ROUND 참여권 jti는 필수입니다");
        new RoundRoomId(claims.roomId());
        if (!"participant".equals(claims.role())) {
            throw new IllegalArgumentException("ROUND 참여권 role은 participant여야 합니다");
        }
        Instant issuedAt = Objects.requireNonNull(
                claims.issuedAt(),
                "ROUND 참여권 발급 시각은 필수입니다"
        );
        Instant expiresAt = Objects.requireNonNull(
                claims.expiresAt(),
                "ROUND 참여권 만료 시각은 필수입니다"
        );
        if (!issuedAt.plusSeconds(GRANT_LIFETIME_SECONDS).equals(expiresAt)) {
            throw new IllegalArgumentException("ROUND 참여권 수명은 정확히 300초여야 합니다");
        }
    }

    private static String requireSecureIssuer(String value) {
        String required = requireText(value, "ROUND 참여권 issuer는 필수입니다");
        ExternalHttpOrigin.requireHttps("ROUND 참여권 issuer", required);
        return required;
    }

    private static String requireKid(String kid) {
        String required = requireText(kid, "ROUND JWK kid는 필수입니다");
        if (!KID_PATTERN.matcher(required).matches()) {
            throw new IllegalStateException(
                    "ROUND JWK kid는 [A-Za-z0-9][A-Za-z0-9._:-]{0,127} 형식이어야 합니다"
            );
        }
        return required;
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(message);
        }
        return value;
    }

    private static RSAPublicKey readPublicKey(Path path) {
        try (InputStream input = openKey(path, "ROUND RSA public key")) {
            return Objects.requireNonNull(
                    RsaKeyConverters.x509().convert(input),
                    "ROUND RSA public key를 읽을 수 없습니다"
            );
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "ROUND RSA public key 파일을 읽을 수 없습니다",
                    exception
            );
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "ROUND RSA public key PEM 형식이 올바르지 않습니다",
                    exception
            );
        }
    }

    private static RSAPrivateKey readPrivateKey(Path path) {
        try (InputStream input = openKey(path, "ROUND RSA private key")) {
            return Objects.requireNonNull(
                    RsaKeyConverters.pkcs8().convert(input),
                    "ROUND RSA private key를 읽을 수 없습니다"
            );
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "ROUND RSA private key 파일을 읽을 수 없습니다",
                    exception
            );
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "ROUND RSA private key PEM 형식이 올바르지 않습니다",
                    exception
            );
        }
    }

    private static InputStream openKey(Path path, String description) {
        if (path == null) {
            throw new IllegalStateException(description + " 파일 경로는 필수입니다");
        }
        if (!path.isAbsolute()) {
            throw new IllegalStateException(description + " 파일 경로는 절대 경로여야 합니다");
        }
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException(description + " 파일을 읽을 수 없습니다");
        }
        try {
            return Files.newInputStream(path);
        } catch (IOException exception) {
            throw new IllegalStateException(description + " 파일을 읽을 수 없습니다", exception);
        }
    }

    private static void requireStrongKey(RSAPublicKey publicKey, String kid) {
        if (publicKey.getModulus().bitLength() < MINIMUM_RSA_BITS) {
            throw new IllegalStateException(
                    "ROUND RSA key는 2048비트 이상이어야 합니다: " + kid
            );
        }
    }

    private static void requireMatchingKeyPair(
            RSAPublicKey publicKey,
            RSAPrivateKey privateKey
    ) {
        try {
            byte[] challenge = "BATON ROUND key-pair validation"
                    .getBytes(StandardCharsets.US_ASCII);
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey);
            signature.update(challenge);
            byte[] signed = signature.sign();
            signature.initVerify(publicKey);
            signature.update(challenge);
            if (!signature.verify(signed)) {
                throw new IllegalStateException(
                        "현재 ROUND RSA public/private key가 서로 일치하지 않습니다"
                );
            }
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(
                    "현재 ROUND RSA public/private key를 검증할 수 없습니다",
                    exception
            );
        }
    }

    private static RSAKey rsaJwk(
            String kid,
            RSAPublicKey publicKey,
            RSAPrivateKey privateKey
    ) {
        RSAKey.Builder builder = new RSAKey.Builder(publicKey)
                .keyUse(KeyUse.SIGNATURE)
                .algorithm(JWSAlgorithm.RS256)
                .keyID(kid);
        if (privateKey != null) {
            builder.privateKey(privateKey);
        }
        return builder.build();
    }

    public record SigningKeyMaterial(
            String kid,
            Path privateKeyPath,
            Path publicKeyPath
    ) {
    }

    public record PublicKeyMaterial(String kid, Path publicKeyPath) {
    }
}
