package com.personal.baton.adapter.out.external.identity;

import com.personal.baton.adapter.out.external.http.ExternalHttpOrigin;
import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("baton.identity.email-verification")
public record IdentityEmailVerificationProperties(
        @DefaultValue("disabled") Delivery delivery,
        @DefaultValue("") String publicBaseUrl,
        @DefaultValue("") String fromAddress,
        @DefaultValue("") String outboxEncryptionKey
) {

    public URI requiredPublicOrigin() {
        URI uri = ExternalHttpOrigin.requireHttps("이메일 인증 공개 주소", publicBaseUrl);
        return URI.create(uri.getScheme() + "://" + uri.getRawAuthority());
    }

    @Override
    public String toString() {
        return "IdentityEmailVerificationProperties[delivery=" + delivery
                + ", publicBaseUrl=" + publicBaseUrl
                + ", fromAddress=" + fromAddress
                + ", outboxEncryptionKey=<redacted>]";
    }

    public enum Delivery {
        DISABLED,
        SMTP
    }
}
