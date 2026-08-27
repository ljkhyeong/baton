package com.personal.baton.adapter.in.web.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.personal.baton.application.crypto.DomainSeparatedSha256;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public final class AuthRateLimiter {

    private static final Limit REGISTRATION_IP = new Limit(12, Duration.ofHours(1));
    private static final Limit REGISTRATION_EMAIL = new Limit(3, Duration.ofHours(1));
    private static final Limit LOGIN_IP = new Limit(60, Duration.ofMinutes(10));
    private static final Limit LOGIN_IP_EMAIL = new Limit(10, Duration.ofMinutes(10));
    private static final Limit LOGIN_ACCOUNT_FAILURE = new Limit(20, Duration.ofMinutes(10));
    private static final Limit VERIFICATION_IP = new Limit(30, Duration.ofMinutes(10));
    private static final Limit VERIFICATION_TOKEN = new Limit(5, Duration.ofMinutes(10));
    private static final Limit ROUND_ACCOUNT_ROOM = new Limit(12, Duration.ofMinutes(1));
    private static final Limit ROUND_NETWORK = new Limit(120, Duration.ofMinutes(1));
    private static final Pattern IPV6_LITERAL = Pattern.compile("[0-9a-fA-F:.]+");

    private final Cache<String, Bucket> buckets;

    public AuthRateLimiter() {
        this(Caffeine.newBuilder()
                .maximumSize(50_000)
                .expireAfterAccess(Duration.ofHours(1))
                .build());
    }

    AuthRateLimiter(Cache<String, Bucket> buckets) {
        this.buckets = Objects.requireNonNull(buckets);
    }

    public void checkRegistration(String remoteAddress, String email) {
        consume(
                "registration:ip:" + digest(normalizeClientNetwork(remoteAddress)),
                REGISTRATION_IP
        );
        consume("registration:email:" + digest(normalizeEmail(email)), REGISTRATION_EMAIL);
    }

    public void checkLogin(String remoteAddress, String email) {
        String normalizedAddress = normalizeClientNetwork(remoteAddress);
        String normalizedEmail = normalizeEmail(email);
        consume("login:ip:" + digest(normalizedAddress), LOGIN_IP);
        consume(
                "login:ip-email:" + digest(normalizedAddress + "\u0000" + normalizedEmail),
                LOGIN_IP_EMAIL
        );
        consume(loginAccountKey(normalizedEmail), LOGIN_ACCOUNT_FAILURE);
    }

    public void recordLoginSuccess(String email) {
        Bucket accountBucket = buckets.getIfPresent(loginAccountKey(normalizeEmail(email)));
        if (accountBucket != null) {
            accountBucket.reset();
        }
    }

    public void recordLoginInfrastructureFailure(String email) {
        Bucket accountBucket = buckets.getIfPresent(loginAccountKey(normalizeEmail(email)));
        if (accountBucket != null) {
            accountBucket.addTokens(1);
        }
    }

    public void checkVerification(String remoteAddress, String verificationToken) {
        consume(
                "verification:ip:" + digest(normalizeClientNetwork(remoteAddress)),
                VERIFICATION_IP
        );
        consume("verification:token:" + digest(verificationToken), VERIFICATION_TOKEN);
    }

    public void checkRoundGrant(String remoteAddress, UUID accountId, String roomId) {
        String normalizedNetwork = normalizeClientNetwork(remoteAddress);
        consume("round:network:" + digest(normalizedNetwork), ROUND_NETWORK);
        consume(
                "round:account-room:" + digest(
                        Objects.requireNonNull(accountId) + "\u0000"
                                + Objects.requireNonNull(roomId)
                ),
                ROUND_ACCOUNT_ROOM
        );
    }

    private void consume(String key, Limit limit) {
        Bucket bucket = buckets.get(key, ignored -> Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(limit.capacity())
                        .refillGreedy(limit.capacity(), limit.window())
                        .build())
                .build());
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (!probe.isConsumed()) {
            long retryAfterSeconds = Math.ceilDiv(
                    probe.getNanosToWaitForRefill(),
                    TimeUnit.SECONDS.toNanos(1L)
            );
            throw new AuthRateLimitExceededException(retryAfterSeconds);
        }
    }

    private String normalizeEmail(String email) {
        return Objects.requireNonNullElse(email, "")
                .strip()
                .toLowerCase(Locale.ROOT);
    }

    private String loginAccountKey(String normalizedEmail) {
        return "login:account-failure:" + digest(normalizedEmail);
    }

    private String normalizeClientNetwork(String remoteAddress) {
        String candidate = Objects.requireNonNullElse(remoteAddress, "").strip();
        int zoneDelimiter = candidate.indexOf('%');
        if (zoneDelimiter >= 0) {
            candidate = candidate.substring(0, zoneDelimiter);
        }
        if (!isNumericAddressLiteral(candidate)) {
            return "unknown";
        }
        try {
            InetAddress address = InetAddress.getByName(candidate);
            byte[] bytes = address.getAddress();
            if (bytes.length == 4) {
                return "ipv4:" + address.getHostAddress();
            }
            return "ipv6:" + HexFormat.of().formatHex(bytes, 0, 8) + "/64";
        } catch (UnknownHostException exception) {
            return "unknown";
        }
    }

    private boolean isNumericAddressLiteral(String candidate) {
        if (candidate.indexOf(':') >= 0) {
            return IPV6_LITERAL.matcher(candidate).matches();
        }
        String[] octets = candidate.split("\\.", -1);
        if (octets.length != 4) {
            return false;
        }
        for (String octet : octets) {
            if (octet.isEmpty() || octet.length() > 3) {
                return false;
            }
            for (int index = 0; index < octet.length(); index += 1) {
                char character = octet.charAt(index);
                if (character < '0' || character > '9') {
                    return false;
                }
            }
            if (Integer.parseInt(octet) > 255) {
                return false;
            }
        }
        return true;
    }

    private String digest(String value) {
        return DomainSeparatedSha256.hashUtf8Hex(Objects.requireNonNullElse(value, ""));
    }

    private record Limit(long capacity, Duration window) {
    }
}
