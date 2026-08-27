package com.personal.baton.application.workspace;

import com.personal.baton.application.workspace.WorkspaceContentIdempotency.ContentCreationAttempt;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.CreateMemberCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.MemberResult;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.UpdateMemberCommand;
import com.personal.baton.application.workspace.port.out.WorkspaceRepository;
import com.personal.baton.domain.workspace.ContentCreationOperation;
import com.personal.baton.domain.workspace.Member;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

final class WorkspaceMemberCoordinator {

    private final WorkspaceRepository repository;
    private final Clock clock;
    private final WorkspaceContentIdempotency contentIdempotency;
    private final WorkspaceMemberResolver memberResolver;
    private final WorkspaceResultMapper resultMapper;

    WorkspaceMemberCoordinator(
            WorkspaceRepository repository,
            Clock clock,
            WorkspaceContentIdempotency contentIdempotency,
            WorkspaceMemberResolver memberResolver,
            WorkspaceResultMapper resultMapper
    ) {
        this.repository = repository;
        this.clock = clock;
        this.contentIdempotency = contentIdempotency;
        this.memberResolver = memberResolver;
        this.resultMapper = resultMapper;
    }

    MemberResult create(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            CreateMemberCommand command
    ) {
        Member member = Member.create(UUID.randomUUID(), teamId, command.name());
        ContentCreationAttempt attempt = contentIdempotency.prepare(
                teamId,
                seasonId,
                ContentCreationOperation.MEMBER,
                idempotencyKey,
                contentIdempotency.fingerprintMemberRequest(teamId, seasonId, member),
                member.getId()
        );
        if (attempt.replayResourceId() != null) {
            Member existing = repository.findMemberById(attempt.replayResourceId())
                    .filter(found -> found.getTeamId().equals(teamId))
                    .orElseThrow(() ->
                            contentIdempotency.missingResource(ContentCreationOperation.MEMBER));
            return resultMapper.toMemberResult(existing);
        }
        contentIdempotency.reserve(attempt);
        return resultMapper.toMemberResult(repository.saveMember(member));
    }

    MemberResult update(
            UUID teamId,
            UUID memberId,
            UpdateMemberCommand command
    ) {
        Member member = memberResolver.requireMember(teamId, memberId);
        member.rename(command.name());
        return resultMapper.toMemberResult(repository.saveMember(member));
    }

    MemberResult updateDeactivation(
            UUID teamId,
            UUID memberId,
            boolean deactivated
    ) {
        Member member = memberResolver.requireMember(teamId, memberId);
        member.updateDeactivation(deactivated, Instant.now(clock));
        return resultMapper.toMemberResult(repository.saveMember(member));
    }
}
