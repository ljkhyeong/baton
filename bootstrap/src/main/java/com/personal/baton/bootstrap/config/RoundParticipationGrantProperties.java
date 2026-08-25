package com.personal.baton.bootstrap.config;

import java.nio.file.Path;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("baton.round.participation-grant")
public record RoundParticipationGrantProperties(
        boolean enabled,
        @DefaultValue("") String issuer,
        @DefaultValue("round") String audience,
        SigningKey currentKey,
        List<PublicKey> previousPublicKeys
) {

    private static final String DEFAULT_AUDIENCE = "round";

    public RoundParticipationGrantProperties {
        audience = audience.isBlank() ? DEFAULT_AUDIENCE : audience;
        previousPublicKeys = previousPublicKeys == null
                ? List.of()
                : List.copyOf(previousPublicKeys);
    }

    List<PublicKey> configuredPreviousPublicKeys() {
        return previousPublicKeys.stream()
                .filter(PublicKey::isConfigured)
                .toList();
    }

    @Override
    public String toString() {
        return "RoundParticipationGrantProperties[enabled=" + enabled
                + ", issuer=" + issuer
                + ", audience=" + audience
                + ", currentKey=<redacted>, previousPublicKeys=<redacted>]";
    }

    public record SigningKey(
            String kid,
            Path privateKeyPath,
            Path publicKeyPath
    ) {
    }

    public record PublicKey(String kid, Path publicKeyPath) {

        boolean isConfigured() {
            boolean hasKid = kid != null && !kid.isBlank();
            boolean hasPath = publicKeyPath != null
                    && !publicKeyPath.toString().isBlank();
            if (hasKid != hasPath) {
                throw new IllegalStateException(
                        "이전 ROUND public key는 kid와 파일 경로를 함께 설정해야 합니다"
                );
            }
            return hasKid;
        }
    }
}
