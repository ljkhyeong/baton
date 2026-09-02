package com.personal.baton.application.workspace;

import com.personal.baton.domain.workspace.DomainValidationException;
import java.util.regex.Pattern;

final class WorkspaceIdempotencyKeyPolicy {

    private static final Pattern VALID_KEY = Pattern.compile("[A-Za-z0-9._~-]{32,200}");

    private WorkspaceIdempotencyKeyPolicy() {
    }

    static void requireValid(String idempotencyKey) {
        if (idempotencyKey == null || !VALID_KEY.matcher(idempotencyKey).matches()) {
            throw new DomainValidationException(
                    "멱등 키는 32자 이상 200자 이하의 URL 안전 ASCII 문자여야 합니다"
            );
        }
    }
}
