package com.personal.baton.adapter.in.web.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public final class AuthRateLimiter {

    private static final Limit REGISTRATION_IP = new Limit(12, Duration.ofHours(1));
    private static final Limit REGISTRATION_EMAIL = new Limit(3, Duration.ofHours(1));
    private static final Limit LOGIN_IP = new Limit(60, Duration.ofMinutes(10));
    private static final Limit LOGIN_IP_EMAIL = new Limit(10, Duration.ofMinutes(10));
    private static final Limit VERIFICATION_IP = new Limit(30, Duration.ofMinutes(10));
    private static final Limit VERIFICATION_TOKEN = new Limit(5, Duration.ofMinutes(10));

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
        consume("registration:ip:" + digest(remoteAddress), REGISTRATION_IP);
        consume("registration:email:" + digest(normalizeEmail(email)), REGISTRATION_EMAIL);
    }

    public void checkLogin(String remoteAddress, String email) {
        String normalizedAddress = Objects.requireNonNullElse(remoteAddress, "");
        String normalizedEmail = normalizeEmail(email);
        consume("login:ip:" + digest(normalizedAddress), LOGIN_IP);
        consume(
                "login:ip-email:" + digest(normalizedAddress + "\u0000" + normalizedEmail),
                LOGIN_IP_EMAIL
        );
    }

    public void checkVerification(String remoteAddress, String verificationToken) {
        consume("verification:ip:" + digest(remoteAddress), VERIFICATION_IP);
        consume("verification:token:" + digest(verificationToken), VERIFICATION_TOKEN);
    }

    private void consume(String key, Limit limit) {
        Bucket bucket = buckets.get(key, ignored -> Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(limit.capacity())
                        .refillGreedy(limit.capacity(), limit.window())
                        .build())
                .build());
        ConsumptionProbe probe = Objects.requireNonNull(bucket).tryConsumeAndReturnRemaining(1);
        if (!probe.isConsumed()) {
            long retryAfterSeconds = Math.max(
                    1L,
                    TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()) + 1L
            );
            throw new AuthRateLimitExceededException(retryAfterSeconds);
        }
    }

    private String normalizeEmail(String email) {
        return Objects.requireNonNullElse(email, "")
                .strip()
                .toLowerCase(Locale.ROOT);
    }

    private String digest(String value) {
        try {
            byte[] hashed = MessageDigest.getInstance("SHA-256").digest(
                    Objects.requireNonNullElse(value, "")
                            .getBytes(StandardCharsets.UTF_8)
            );
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다", exception);
        }
    }

    private record Limit(long capacity, Duration window) {
    }
}
