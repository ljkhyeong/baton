package com.personal.baton.application.workspace.port.in;

import com.personal.baton.application.identity.port.in.MemberIdentityUseCase.AuthenticatedAccount;
import java.util.Objects;

public sealed interface WorkspaceAuthorization
        permits WorkspaceAuthorization.LegacyAccessKey, WorkspaceAuthorization.SessionAccount {

    record LegacyAccessKey(String accessKey) implements WorkspaceAuthorization {
    }

    record SessionAccount(
            AuthenticatedAccount authenticatedAccount
    ) implements WorkspaceAuthorization {

        public SessionAccount {
            Objects.requireNonNull(
                    authenticatedAccount,
                    "세션 인증 계정은 필수입니다"
            );
        }
    }
}
