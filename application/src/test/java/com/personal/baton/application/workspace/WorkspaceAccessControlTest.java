package com.personal.baton.application.workspace;

import com.personal.baton.application.workspace.WorkspaceAccessControl.AccessKeyChange;
import com.personal.baton.application.workspace.WorkspaceAccessControl.AccessKeyChangeKind;
import com.personal.baton.application.workspace.error.WorkspaceAccessDeniedException;
import com.personal.baton.application.workspace.error.WorkspaceCreationDeniedException;
import com.personal.baton.application.workspace.error.WorkspaceRecoveryDeniedException;
import com.personal.baton.domain.workspace.Team;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("policy")
class WorkspaceAccessControlTest {

    private static final UUID TEAM_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SEASON_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String IDEMPOTENCY_KEY = "workspace-idempotency-primary-000001";

    @DisplayName("공유 키 파생 결과는 기존 워크스페이스와 키 변경 호환 벡터를 유지한다")
    @Test
    void preservesAccessKeyDerivationCompatibilityVectors() {
        WorkspaceAccessControl accessControl = accessControl("", "");

        assertThat(accessControl.deriveInitialAccessKey(IDEMPOTENCY_KEY))
                .isEqualTo("sIRgg9bOEBilomDeDJOnSuuTeOUtJXDYkKY5ePxEOAU");

        AccessKeyChange rotation = accessControl.deriveAccessKeyChange(
                AccessKeyChangeKind.ROTATE,
                TEAM_ID,
                IDEMPOTENCY_KEY
        );
        assertThat(rotation.idempotencyHash())
                .isEqualTo("e801538dda26ee9e32cd0e3c1e5044d2de604b1e0cf059a8b4d758e924681a80");
        assertThat(rotation.accessKey())
                .isEqualTo("h-7Hg-KqBadBqZH1eOMKpOqkUGHAyB6NScbdLJz67V0");

        AccessKeyChange legacyRecovery = accessControl.deriveLegacyAccessKeyChange(
                AccessKeyChangeKind.RECOVER,
                TEAM_ID,
                SEASON_ID,
                IDEMPOTENCY_KEY
        );
        assertThat(legacyRecovery.idempotencyHash())
                .isEqualTo("ba0a6c3cc5776fa70418495e1e16f3190f1d9e126295e62f9f32421d484c4c21");
        assertThat(legacyRecovery.accessKey())
                .isEqualTo("EhqJSarevbQbHkPe0hhSlR07xE9mprbvtm3Oi_hsYhg");
    }

    @DisplayName("로컬 생성은 비밀값이 없으면 허용하지만 복구는 항상 거절한다")
    @Test
    void keepsLocalCreationOpenAndRecoveryClosedWithoutConfiguredSecrets() {
        WorkspaceAccessControl accessControl = accessControl("", "");

        assertThatCode(() -> accessControl.verifyWorkspaceCreationPermission(null))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> accessControl.verifyWorkspaceRecoveryPermission(null))
                .isInstanceOf(WorkspaceRecoveryDeniedException.class);
    }

    @DisplayName("설정한 생성 비밀과 복구 비밀은 서로 독립적으로 정확히 일치해야 한다")
    @Test
    void verifiesConfiguredOperatorSecretsIndependently() {
        WorkspaceAccessControl accessControl = accessControl(
                "pilot-creation-secret",
                "pilot-recovery-secret"
        );

        assertThatCode(() ->
                accessControl.verifyWorkspaceCreationPermission("pilot-creation-secret"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() ->
                accessControl.verifyWorkspaceCreationPermission("pilot-recovery-secret"))
                .isInstanceOf(WorkspaceCreationDeniedException.class);

        assertThatCode(() ->
                accessControl.verifyWorkspaceRecoveryPermission("pilot-recovery-secret"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() ->
                accessControl.verifyWorkspaceRecoveryPermission("pilot-creation-secret"))
                .isInstanceOf(WorkspaceRecoveryDeniedException.class);
    }

    @DisplayName("접근 키는 저장한 해시와 상수 시간 비교하고 잘못된 저장 해시는 내부 오류로 구분한다")
    @Test
    void verifiesAccessKeyHashAndRejectsMalformedStoredHash() {
        WorkspaceAccessControl accessControl = accessControl("", "");
        String accessKey = "workspace-access-key";
        Team team = Team.create(
                TEAM_ID,
                "알고리즘 한 바퀴",
                accessControl.hashAccessKey(accessKey)
        );

        assertThatCode(() -> accessControl.verifyAccessKey(team, accessKey))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> accessControl.verifyAccessKey(team, "wrong-access-key"))
                .isInstanceOf(WorkspaceAccessDeniedException.class);

        Team malformed = mock(Team.class);
        when(malformed.getAccessKeyHash()).thenReturn("not-a-sha256-hash");
        assertThatThrownBy(() -> accessControl.matchesAccessKey(malformed, accessKey))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("저장된 접근 키 해시가 올바르지 않습니다");
    }

    private WorkspaceAccessControl accessControl(String creationKey, String recoveryKey) {
        return new WorkspaceAccessControl(new WorkspaceSecrets(creationKey, recoveryKey));
    }
}
