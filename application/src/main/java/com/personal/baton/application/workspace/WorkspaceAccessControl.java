package com.personal.baton.application.workspace;

import com.personal.baton.application.workspace.error.WorkspaceAccessDeniedException;
import com.personal.baton.application.workspace.error.WorkspaceCreationDeniedException;
import com.personal.baton.application.workspace.error.WorkspaceRecoveryDeniedException;
import com.personal.baton.domain.workspace.Team;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

final class WorkspaceAccessControl {

    private static final String ACCESS_KEY_DERIVATION_DOMAIN = "baton:workspace-access:v1";
    private static final String ROTATE_IDEMPOTENCY_HASH_DOMAIN =
            "baton:workspace-access-key-rotate-idempotency:v1";
    private static final String ROTATE_ACCESS_KEY_DERIVATION_DOMAIN =
            "baton:workspace-access-key-rotate:v1";
    private static final String RECOVER_IDEMPOTENCY_HASH_DOMAIN =
            "baton:workspace-access-key-recover-idempotency:v1";
    private static final String RECOVER_ACCESS_KEY_DERIVATION_DOMAIN =
            "baton:workspace-access-key-recover:v1";

    private final String workspaceCreationKey;
    private final String workspaceRecoveryKey;

    WorkspaceAccessControl(String workspaceCreationKey, String workspaceRecoveryKey) {
        this.workspaceCreationKey = workspaceCreationKey == null ? "" : workspaceCreationKey;
        this.workspaceRecoveryKey = workspaceRecoveryKey == null ? "" : workspaceRecoveryKey;
    }

    void verifyWorkspaceCreationPermission(String creationKey) {
        if (workspaceCreationKey.isBlank()) {
            return;
        }
        if (!matchesConfiguredSecret(workspaceCreationKey, creationKey)) {
            throw new WorkspaceCreationDeniedException();
        }
    }

    void verifyWorkspaceRecoveryPermission(String recoveryKey) {
        if (workspaceRecoveryKey.isBlank()
                || !matchesConfiguredSecret(workspaceRecoveryKey, recoveryKey)) {
            throw new WorkspaceRecoveryDeniedException();
        }
    }

    void verifyAccessKey(Team team, String accessKey) {
        if (accessKey == null || accessKey.isBlank() || !matchesAccessKey(team, accessKey)) {
            throw new WorkspaceAccessDeniedException();
        }
    }

    boolean matchesAccessKey(Team team, String accessKey) {
        if (accessKey == null) {
            return false;
        }
        byte[] expected;
        try {
            expected = HexFormat.of().parseHex(team.getAccessKeyHash());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("저장된 접근 키 해시가 올바르지 않습니다", exception);
        }
        return MessageDigest.isEqual(expected, DomainSeparatedSha256.hashUtf8(accessKey));
    }

    String deriveInitialAccessKey(String idempotencyKey) {
        byte[] derived = DomainSeparatedSha256.hash(
                ACCESS_KEY_DERIVATION_DOMAIN,
                List.of(idempotencyKey)
        );
        return Base64.getUrlEncoder().withoutPadding().encodeToString(derived);
    }

    String hashAccessKey(String accessKey) {
        return DomainSeparatedSha256.hashUtf8Hex(accessKey);
    }

    AccessKeyChange deriveAccessKeyChange(
            AccessKeyChangeKind kind,
            UUID teamId,
            String idempotencyKey
    ) {
        return deriveAccessKeyChange(
                kind.idempotencyHashDomain,
                kind.accessKeyDomain,
                List.of(teamId.toString(), idempotencyKey)
        );
    }

    AccessKeyChange deriveLegacyAccessKeyChange(
            AccessKeyChangeKind kind,
            UUID teamId,
            UUID seasonId,
            String idempotencyKey
    ) {
        return deriveAccessKeyChange(
                kind.idempotencyHashDomain,
                kind.accessKeyDomain,
                List.of(teamId.toString(), seasonId.toString(), idempotencyKey)
        );
    }

    private AccessKeyChange deriveAccessKeyChange(
            String idempotencyHashDomain,
            String accessKeyDomain,
            List<String> values
    ) {
        String idempotencyHash = DomainSeparatedSha256.hashHex(
                idempotencyHashDomain,
                values
        );
        String accessKey = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(DomainSeparatedSha256.hash(accessKeyDomain, values));
        return new AccessKeyChange(idempotencyHash, accessKey);
    }

    private boolean matchesConfiguredSecret(String configuredSecret, String presentedSecret) {
        if (presentedSecret == null) {
            return false;
        }
        byte[] expected = configuredSecret.getBytes(StandardCharsets.UTF_8);
        byte[] actual = presentedSecret.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }

    enum AccessKeyChangeKind {
        ROTATE(ROTATE_IDEMPOTENCY_HASH_DOMAIN, ROTATE_ACCESS_KEY_DERIVATION_DOMAIN),
        RECOVER(RECOVER_IDEMPOTENCY_HASH_DOMAIN, RECOVER_ACCESS_KEY_DERIVATION_DOMAIN);

        private final String idempotencyHashDomain;
        private final String accessKeyDomain;

        AccessKeyChangeKind(String idempotencyHashDomain, String accessKeyDomain) {
            this.idempotencyHashDomain = idempotencyHashDomain;
            this.accessKeyDomain = accessKeyDomain;
        }
    }

    record AccessKeyChange(String idempotencyHash, String accessKey) {
    }
}
