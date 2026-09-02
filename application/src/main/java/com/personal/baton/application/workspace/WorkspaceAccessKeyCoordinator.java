package com.personal.baton.application.workspace;

import org.springframework.stereotype.Component;
import com.personal.baton.application.workspace.WorkspaceAccessControl.AccessKeyChange;
import com.personal.baton.application.workspace.WorkspaceAccessControl.AccessKeyChangeKind;
import com.personal.baton.application.workspace.error.IdempotencyReplayExpiredException;
import com.personal.baton.application.workspace.port.in.WorkspaceContract.AccessKeyResult;
import com.personal.baton.application.workspace.port.out.WorkspaceAccessRepository;
import com.personal.baton.application.workspace.port.out.WorkspaceSeasonRepository;
import com.personal.baton.domain.workspace.AccessKeyChangeHistory;
import com.personal.baton.domain.workspace.Team;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Component
final class WorkspaceAccessKeyCoordinator {

    private final WorkspaceAccessRepository accessRepository;
    private final WorkspaceSeasonRepository seasonRepository;
    private final WorkspaceScopeAuthorizer scopeAuthorizer;
    private final WorkspaceAccessControl accessControl;

    WorkspaceAccessKeyCoordinator(
            WorkspaceAccessRepository accessRepository,
            WorkspaceSeasonRepository seasonRepository,
            WorkspaceScopeAuthorizer scopeAuthorizer,
            WorkspaceAccessControl accessControl
    ) {
        this.accessRepository = accessRepository;
        this.seasonRepository = seasonRepository;
        this.scopeAuthorizer = scopeAuthorizer;
        this.accessControl = accessControl;
    }

    AccessKeyResult rotate(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            String currentAccessKey
    ) {
        WorkspaceScope scope = scopeAuthorizer.requireScope(teamId, seasonId);
        AccessKeyChange change = accessControl.deriveAccessKeyChange(
                AccessKeyChangeKind.ROTATE,
                teamId,
                idempotencyKey
        );
        AccessKeyResult replay = replay(scope.team(), change);
        if (replay != null) {
            return replay;
        }
        AccessKeyResult legacyReplay = replayLegacy(
                scope.team(),
                AccessKeyChangeKind.ROTATE,
                idempotencyKey
        );
        if (legacyReplay != null) {
            return legacyReplay;
        }
        accessControl.verifyAccessKey(scope.team(), currentAccessKey);
        return replace(scope.team(), change);
    }

    AccessKeyResult recover(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey
    ) {
        WorkspaceScope scope = scopeAuthorizer.requireScope(teamId, seasonId);
        AccessKeyChange change = accessControl.deriveAccessKeyChange(
                AccessKeyChangeKind.RECOVER,
                teamId,
                idempotencyKey
        );
        AccessKeyResult replay = replay(scope.team(), change);
        if (replay != null) {
            return replay;
        }
        AccessKeyResult legacyReplay = replayLegacy(
                scope.team(),
                AccessKeyChangeKind.RECOVER,
                idempotencyKey
        );
        if (legacyReplay != null) {
            return legacyReplay;
        }
        return replace(scope.team(), change);
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

    private AccessKeyResult replayLegacy(
            Team team,
            AccessKeyChangeKind kind,
            String idempotencyKey
    ) {
        List<AccessKeyChange> legacyChanges = seasonRepository.findSeasonsByTeamId(team.getId())
                .stream()
                .map(season -> accessControl.deriveLegacyAccessKeyChange(
                        kind,
                        team.getId(),
                        season.getId(),
                        idempotencyKey
                ))
                .toList();
        if (legacyChanges.isEmpty()) {
            return null;
        }
        Set<String> existingHashes = accessRepository.findAccessKeyChangeIdempotencyHashes(
                team.getId(),
                legacyChanges.stream().map(AccessKeyChange::idempotencyHash).toList()
        );
        boolean foundExpiredReplay = false;
        for (AccessKeyChange legacyChange : legacyChanges) {
            if (!existingHashes.contains(legacyChange.idempotencyHash())) {
                continue;
            }
            if (legacyChange.idempotencyHash().equals(
                    team.getLastAccessKeyChangeIdempotencyHash()
            )) {
                if (!accessControl.matchesAccessKey(team, legacyChange.accessKey())) {
                    throw new IllegalStateException(
                            "저장된 접근 키 변경 결과가 기존 멱등 키와 일치하지 않습니다"
                    );
                }
                return new AccessKeyResult(legacyChange.accessKey());
            }
            foundExpiredReplay = true;
        }
        if (foundExpiredReplay) {
            throw new IdempotencyReplayExpiredException();
        }
        return null;
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
