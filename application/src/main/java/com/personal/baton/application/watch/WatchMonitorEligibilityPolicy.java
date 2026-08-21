package com.personal.baton.application.watch;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;

final class WatchMonitorEligibilityPolicy {

    private static final Pattern NUMERIC_ADDRESS_COMPONENT = Pattern.compile(
            "(?:0[xX][0-9A-Fa-f]+|[0-9]+)"
    );
    private static final Pattern ASCII_HOST = Pattern.compile(
            "(?=.{1,253}\\z)(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.)*"
                    + "[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?"
    );

    boolean isEligible(String targetUrl) {
        if (targetUrl == null || targetUrl.length() > 2_048 || containsUnsafeCharacter(targetUrl)) {
            return false;
        }

        URI uri;
        try {
            uri = URI.create(targetUrl);
        } catch (IllegalArgumentException exception) {
            return false;
        }

        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null || host.isBlank()) {
            return false;
        }
        String normalizedScheme = scheme.toLowerCase(Locale.ROOT);
        if (!normalizedScheme.equals("http") && !normalizedScheme.equals("https")) {
            return false;
        }
        if (uri.getUserInfo() != null || uri.getRawFragment() != null || uri.getRawQuery() != null) {
            return false;
        }
        int port = uri.getPort();
        if (port != -1
                && !(normalizedScheme.equals("http") && port == 80)
                && !(normalizedScheme.equals("https") && port == 443)) {
            return false;
        }
        String expectedAuthority = port == -1 ? host : host + ":" + port;
        return ASCII_HOST.matcher(host).matches()
                && !isIpLiteral(host)
                && uri.getRawAuthority().equalsIgnoreCase(expectedAuthority);
    }

    private boolean isIpLiteral(String host) {
        if (host.contains(":") || host.startsWith("[") || host.endsWith("]")) {
            return true;
        }
        String[] components = host.split("\\.", -1);
        for (String component : components) {
            if (!NUMERIC_ADDRESS_COMPONENT.matcher(component).matches()) {
                return false;
            }
        }
        return true;
    }

    private boolean containsUnsafeCharacter(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isISOControl(character) || character == '\\') {
                return true;
            }
        }
        return false;
    }
}
