package com.personal.baton.application.workspace;

import org.springframework.stereotype.Component;
import com.personal.baton.application.calendar.port.out.CalendarSubscriptionStore;
import com.personal.baton.application.workspace.WorkspaceContentIdempotency.ContentCreationAttempt;
import com.personal.baton.application.workspace.port.in.WorkspacePeopleCommands.CreateMemberCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceContract.MemberResult;
import com.personal.baton.application.workspace.port.in.WorkspacePeopleCommands.UpdateMemberCommand;
import com.personal.baton.application.workspace.port.out.WorkspacePeopleRepository;
import com.personal.baton.domain.workspace.ContentCreationOperation;
import com.personal.baton.domain.workspace.Member;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Component
final class WorkspaceMemberCoordinator {

    private final WorkspacePeopleRepository repository;
    private final Clock clock;
    private final CalendarSubscriptionStore calendarSubscriptions;
    private final WorkspaceContentIdempotency contentIdempotency;
    private final WorkspaceMemberResolver memberResolver;
    private final WorkspaceResultMapper resultMapper;
    private final BriefContinuitySignalRecorder briefContinuitySignalRecorder;

    WorkspaceMemberCoordinator(
            WorkspacePeopleRepository repository,
            Clock clock,
            WorkspaceContentIdempotency contentIdempotency,
            WorkspaceMemberResolver memberResolver,
            WorkspaceResultMapper resultMapper,
            BriefContinuitySignalRecorder briefContinuitySignalRecorder,
            CalendarSubscriptionStore calendarSubscriptions
    ) {
        this.repository = repository;
        this.clock = clock;
        this.calendarSubscriptions = calendarSubscriptions;
        this.contentIdempotency = contentIdempotency;
        this.memberResolver = memberResolver;
        this.resultMapper = resultMapper;
        this.briefContinuitySignalRecorder = briefContinuitySignalRecorder;
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
                member.getId(),
                member.getName()
        );
        Optional<Member> replayed = attempt.replay(id -> repository.findMemberById(id)
                .filter(found -> found.getTeamId().equals(teamId)));
        if (replayed.isPresent()) {
            return resultMapper.toMemberResult(replayed.get());
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
            UUID seasonId,
            UUID memberId,
            boolean deactivated
    ) {
        Member member = memberResolver.requireMember(teamId, memberId);
        boolean changed = member.isActive() == deactivated;
        member.updateDeactivation(deactivated, Instant.now(clock));
        Member saved = repository.saveMember(member);
        if (deactivated) {
            calendarSubscriptions.requestMemberRevocation(teamId, memberId);
        }
        if (changed) {
            briefContinuitySignalRecorder.reconcileSeason(teamId, seasonId);
        }
        return resultMapper.toMemberResult(saved);
    }
}
