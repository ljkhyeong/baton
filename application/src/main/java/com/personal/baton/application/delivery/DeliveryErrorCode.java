package com.personal.baton.application.delivery;

public final class DeliveryErrorCode {

    private static final int MAX_LENGTH = 64;

    private DeliveryErrorCode() {
    }

    public static String normalized(String code, String fallback) {
        if (code == null || code.isBlank()) {
            return fallback;
        }
        return code.length() <= MAX_LENGTH ? code : code.substring(0, MAX_LENGTH);
    }
}
