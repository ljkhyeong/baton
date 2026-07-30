package com.personal.baton.adapter.out.external.round;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties("baton.round.grant")
public class RoundParticipationGrantProperties {

    private boolean enabled;
    private URI issuer;
    private String audience = "round";
    private Duration ttl = Duration.ofMinutes(5);
    private String cookieName = "__Secure-round_access";
    private URI roundPublicOrigin;
    private String activeKid;
    private String privateKeyPath;
    private String jwkSetPath;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public URI getIssuer() {
        return issuer;
    }

    public void setIssuer(URI issuer) {
        this.issuer = issuer;
    }

    public String getAudience() {
        return audience;
    }

    public void setAudience(String audience) {
        this.audience = audience;
    }

    public Duration getTtl() {
        return ttl;
    }

    public void setTtl(Duration ttl) {
        this.ttl = ttl;
    }

    public String getCookieName() {
        return cookieName;
    }

    public void setCookieName(String cookieName) {
        this.cookieName = cookieName;
    }

    public URI getRoundPublicOrigin() {
        return roundPublicOrigin;
    }

    public void setRoundPublicOrigin(URI roundPublicOrigin) {
        this.roundPublicOrigin = roundPublicOrigin;
    }

    public String getActiveKid() {
        return activeKid;
    }

    public void setActiveKid(String activeKid) {
        this.activeKid = activeKid;
    }

    public String getPrivateKeyPath() {
        return privateKeyPath;
    }

    public void setPrivateKeyPath(String privateKeyPath) {
        this.privateKeyPath = privateKeyPath;
    }

    public String getJwkSetPath() {
        return jwkSetPath;
    }

    public void setJwkSetPath(String jwkSetPath) {
        this.jwkSetPath = jwkSetPath;
    }
}
