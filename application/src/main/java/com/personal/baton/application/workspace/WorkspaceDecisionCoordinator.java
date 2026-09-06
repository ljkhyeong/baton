package com.personal.baton.application.workspace;

import org.springframework.stereotype.Component;
import com.personal.baton.application.workspace.WorkspaceContentIdempotency.ContentCreationAttempt;
import com.personal.baton.application.workspace.error.WorkspaceNotFoundException;
import com.personal.baton.application.workspace.port.in.WorkspaceRecordCommands.CreateDecisionCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceContract.DecisionResult;
import com.personal.baton.application.workspace.port.in.WorkspaceRecordCommands.UpdateDecisionCommand;
import com.personal.baton.application.workspace.port.out.WorkspacePeopleRepository;
import com.personal.baton.application.workspace.port.out.WorkspaceRecordsRepository;
import com.personal.baton.domain.workspace.ContentCreationOperation;
import com.personal.baton.domain.workspace.ContentRecordKind;
import com.personal.baton.domain.workspace.Decision;
import com.personal.baton.domain.workspace.Member;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
final class WorkspaceDecisionCoordinator {

    private final WorkspaceRecordsRepository recordsRepository;
    private final WorkspacePeopleRepository peopleRepository;
    private final Clock clock;
    private final ContentChangeRecorder changes;
    private final WorkspaceContentIdempotency contentIdempotency;
    private final WorkspaceMemberResolver memberResolver;
    private final WorkspaceResultMapper resultMapper;

    WorkspaceDecisionCoordinator(
            WorkspaceRecordsRepository recordsRepository,
            WorkspacePeopleRepository peopleRepository,
            Clock clock,
            WorkspaceContentIdempotency contentIdempotency,
            WorkspaceMemberResolver memberResolver,
            WorkspaceResultMapper resultMapper,
            ContentChangeRecorder changes
    ) {
        this.recordsRepository = recordsRepository;
        this.peopleRepository = peopleRepository;
        this.clock = clock;
        this.changes = changes;
        this.contentIdempotency = contentIdempotency;
        this.memberResolver = memberResolver;
        this.resultMapper = resultMapper;
    }

    DecisionResult create(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            CreateDecisionCommand command
    ) {
        Decision decision = Decision.create(
                UUID.randomUUID(),
                seasonId,
                command.title(),
                command.reason(),
                command.alternative(),
                Instant.now(clock),
                command.authorMemberId(),
                command.roleIds(),
                command.textFormat()
        );
        ContentCreationAttempt attempt = contentIdempotency.prepare(
                teamId,
                seasonId,
                ContentCreationOperation.DECISION,
                idempotencyKey,
                contentIdempotency.fingerprintDecisionRequest(teamId, seasonId, decision),
                decision.getId()
        );
        if (attempt.replayResourceId() != null) {
            Decision existing = recordsRepository.findDecisionById(attempt.replayResourceId())
                    .filter(found -> found.getSeasonId().equals(seasonId))
                    .orElseThrow(() ->
                            contentIdempotency.missingResource(ContentCreationOperation.DECISION));
            Member existingAuthor = memberResolver.requireMember(
                    teamId,
                    existing.getAuthorMemberId()
            );
            return resultMapper.toDecisionResult(
                    existing,
                    Map.of(existingAuthor.getId(), existingAuthor)
            );
        }
        Member author = memberResolver.requireActiveMembersForNewReferences(
                teamId,
                decision.getAuthorMemberId()
        ).get(decision.getAuthorMemberId());
        validateRoleOwnership(teamId, seasonId, decision.getRoleIds());
        contentIdempotency.reserve(attempt);
        Decision saved = recordsRepository.saveDecision(decision);
        return resultMapper.toDecisionResult(saved, Map.of(author.getId(), author));
    }

    DecisionResult update(
            UUID teamId,
            UUID seasonId,
            UUID decisionId,
            UpdateDecisionCommand command
    ) {
        Decision decision = requireActiveDecision(seasonId, decisionId);
        Member previousAuthor = memberResolver.requireMember(teamId, decision.getAuthorMemberId());
        List<UUID> previousRoleIds = List.copyOf(decision.getRoleIds());
        String previousRoleNames = roleNames(teamId, seasonId, previousRoleIds);
        var before = changes.snapshot(decision, previousAuthor.getName(), previousRoleNames);
        Member author = Objects.equals(decision.getAuthorMemberId(), command.authorMemberId())
                ? previousAuthor
                : memberResolver.requireActiveMembersForNewReferences(
                        teamId,
                        command.authorMemberId()
                ).get(command.authorMemberId());
        decision.update(
                command.title(),
                command.reason(),
                command.alternative(),
                command.authorMemberId(),
                command.roleIds(),
                command.textFormat()
        );
        validateRoleOwnership(teamId, seasonId, decision.getRoleIds());
        String roleNames = previousRoleIds.equals(decision.getRoleIds())
                ? previousRoleNames : roleNames(teamId, seasonId, decision.getRoleIds());
        changes.record(teamId, seasonId, ContentRecordKind.DECISION, decisionId, before,
                changes.snapshot(decision, author.getName(), roleNames));
        return resultMapper.toDecisionResult(
                recordsRepository.saveDecision(decision),
                Map.of(author.getId(), author)
        );
    }

    DecisionResult updateArchive(
            UUID teamId,
            UUID seasonId,
            UUID decisionId,
            boolean archived
    ) {
        Decision decision = requireDecision(seasonId, decisionId);
        Member author = memberResolver.requireMember(teamId, decision.getAuthorMemberId());
        String roleNames = roleNames(teamId, seasonId, decision.getRoleIds());
        var before = changes.snapshot(decision, author.getName(), roleNames);
        decision.updateArchive(archived, Instant.now(clock));
        changes.record(teamId, seasonId, ContentRecordKind.DECISION, decisionId, before,
                changes.snapshot(decision, author.getName(), roleNames));
        return resultMapper.toDecisionResult(
                recordsRepository.saveDecision(decision),
                Map.of(author.getId(), author)
        );
    }

    private Decision requireDecision(UUID seasonId, UUID decisionId) {
        return recordsRepository.findDecisionById(decisionId)
                .filter(decision -> decision.getSeasonId().equals(seasonId))
                .orElseThrow(() -> new WorkspaceNotFoundException(
                        "DECISION_NOT_FOUND",
                        "결정 기록을 찾을 수 없습니다"
                ));
    }

    private Decision requireActiveDecision(UUID seasonId, UUID decisionId) {
        Decision decision = requireDecision(seasonId, decisionId);
        if (decision.getArchivedAt() != null) {
            throw new WorkspaceNotFoundException(
                    "DECISION_NOT_FOUND",
                    "결정 기록을 찾을 수 없습니다"
            );
        }
        return decision;
    }

    private String roleNames(UUID teamId, UUID seasonId, List<UUID> roleIds) {
        return peopleRepository.findRoleNames(teamId, seasonId, roleIds).stream()
                .sorted().collect(Collectors.joining(", "));
    }

    private void validateRoleOwnership(UUID teamId, UUID seasonId, List<UUID> roleIds) {
        List<UUID> found = peopleRepository.findExistingRoleIds(teamId, seasonId, roleIds);
        if (found.size() != roleIds.size()) {
            throw new WorkspaceNotFoundException(
                    "ROLE_NOT_FOUND",
                    "관련 역할을 찾을 수 없습니다"
            );
        }
    }
}
