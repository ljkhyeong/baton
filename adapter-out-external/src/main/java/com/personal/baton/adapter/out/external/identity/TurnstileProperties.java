package com.personal.baton.adapter.out.external.identity;

import java.net.IDN;
import java.time.Duration;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("baton.auth.turnstile")
public record TurnstileProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("") String siteKey,
        @DefaultValue("") String secretKey,
        @DefaultValue("") String expectedHostname,
        @DefaultValue("PT2S") Duration connectTimeout,
        @DefaultValue("PT5S") Duration readTimeout
) {

    private static final Pattern HOST_LABEL = Pattern.compile(
            "[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?"
    );

    public void validateEnabled() {
        if (!enabled) {
            return;
        }
        required(siteKey, "Turnstile site key");
        required(secretKey, "Turnstile secret key");
        required(expectedHostname, "Turnstile 허용 호스트");
        validateHostname(expectedHostname);
        if (connectTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative()) {
            throw new IllegalArgumentException("Turnstile 연결 제한 시간은 양수여야 합니다");
        }
        if (readTimeout == null || readTimeout.isZero() || readTimeout.isNegative()) {
            throw new IllegalArgumentException("Turnstile 응답 제한 시간은 양수여야 합니다");
        }
    }

    private static void required(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + "가 필요합니다");
        }
    }

    private static void validateHostname(String value) {
        String asciiHostname;
        try {
            asciiHostname = IDN.toASCII(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Turnstile 허용 호스트 형식이 올바르지 않습니다", exception);
        }
        if (asciiHostname.length() > 253 || asciiHostname.endsWith(".")) {
            throw new IllegalArgumentException("Turnstile 허용 호스트 형식이 올바르지 않습니다");
        }
        for (String label : asciiHostname.split("\\.")) {
            if (!HOST_LABEL.matcher(label).matches()) {
                throw new IllegalArgumentException("Turnstile 허용 호스트 형식이 올바르지 않습니다");
            }
        }
    }

    @Override
    public String toString() {
        return "TurnstileProperties[enabled=" + enabled
                + ", siteKey=" + siteKey
                + ", secretKey=<redacted>, expectedHostname=" + expectedHostname
                + ", connectTimeout=" + connectTimeout
                + ", readTimeout=" + readTimeout + "]";
    }
}
