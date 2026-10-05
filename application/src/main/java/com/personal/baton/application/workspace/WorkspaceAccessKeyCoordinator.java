package com.personal.baton.application.workspace;

import org.springframework.stereotype.Component;
import com.personal.baton.application.workspace.WorkspaceAccessControl.AccessKeyChange;
import com.personal.baton.application.workspace.WorkspaceAccessControl.AccessKeyChangeKind;
import com.personal.baton.application.workspace.error.IdempotencyReplayExpiredException;
import com.personal.baton.application.workspace.port.in.WorkspaceContract.AccessKeyResult;
import com.personal.baton.application.workspace.port.out.WorkspaceAccessRepository;
import com.personal.baton.domain.workspace.AccessKeyChangeHistory;
import com.personal.baton.domain.workspace.Team;
import java.util.UUID;
import java.util.function.Consumer;

@Component
final class WorkspaceAccessKeyCoordinator {

    private final WorkspaceAccessRepository accessRepository;
    private final WorkspaceScopeAuthorizer scopeAuthorizer;
    private final WorkspaceAccessControl accessControl;

    WorkspaceAccessKeyCoordinator(
            WorkspaceAccessRepository accessRepository,
            WorkspaceScopeAuthorizer scopeAuthorizer,
            WorkspaceAccessControl accessControl
    ) {
        this.accessRepository = accessRepository;
        this.scopeAuthorizer = scopeAuthorizer;
        this.accessControl = accessControl;
    }

    AccessKeyResult rotate(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            String currentAccessKey
    ) {
        return change(AccessKeyChangeKind.ROTATE, teamId, seasonId, idempotencyKey,
                team -> accessControl.verifyAccessKey(team, currentAccessKey));
    }

    // 복구 권한은 서비스가 복구 키로 먼저 확인한다.
    AccessKeyResult recover(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey
    ) {
        return change(AccessKeyChangeKind.RECOVER, teamId, seasonId, idempotencyKey, team -> { });
    }

    // 같은 멱등 키의 재요청은 현재 키 확인 없이 처음 발급한 키를 다시 돌려준다.
    private AccessKeyResult change(
            AccessKeyChangeKind kind,
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            Consumer<Team> verifyBeforeChange
    ) {
        Team team = scopeAuthorizer.requireScope(teamId, seasonId).team();
        AccessKeyChange change = accessControl.deriveAccessKeyChange(kind, teamId, idempotencyKey);
        AccessKeyResult replay = replay(team, change);
        if (replay != null) {
            return replay;
        }
        verifyBeforeChange.accept(team);
        return replace(team, change);
    }

    private AccessKeyResult replay(Team team, AccessKeyChange change) {
        if (!accessRepository.existsAccessKeyChangeHistory(team.getId(), change.idempotencyHash())) {
            return null;
        }
        if (!change.idempotencyHash().equals(team.getLastAccessKeyChangeIdempotencyHash())) {
            throw new IdempotencyReplayExpiredException();
        }
        if (!accessControl.matchesAccessKey(team, change.accessKey())) {
            throw new IllegalStateException("저장된 접근 키 변경 결과가 멱등 키와 일치하지 않습니다");
        }
        return new AccessKeyResult(change.accessKey());
    }

    private AccessKeyResult replace(Team team, AccessKeyChange change) {
        team.changeAccessKey(
                accessControl.hashAccessKey(change.accessKey()),
                change.idempotencyHash()
        );
        accessRepository.saveTeam(team);
        accessRepository.saveAccessKeyChangeHistory(AccessKeyChangeHistory.create(
                UUID.randomUUID(),
                team.getId(),
                change.idempotencyHash()
        ));
        return new AccessKeyResult(change.accessKey());
    }
}
