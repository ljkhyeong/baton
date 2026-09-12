package com.personal.baton.application.brief;

import com.personal.baton.application.brief.error.BriefAccessDeniedException;
import com.personal.baton.application.brief.error.BriefEditionNotFoundException;
import com.personal.baton.application.brief.error.BriefGenerationBlockedException;
import com.personal.baton.application.brief.error.BriefGenerationInProgressException;
import com.personal.baton.application.brief.error.BriefIntegrationConfigurationException;
import com.personal.baton.application.brief.error.BriefIntegrationUnavailableException;
import com.personal.baton.application.brief.port.in.BriefEditionUseCase;
import com.personal.baton.application.brief.port.in.BriefAttentionUseCase;
import com.personal.baton.application.brief.port.out.BriefEditionGenerationExecutionPort;
import com.personal.baton.application.brief.port.out.BriefEditionGenerationExecutionPort.ClaimResult;
import com.personal.baton.application.brief.port.out.BriefEditionGenerationExecutionPort.DeliveryBoundary;
import com.personal.baton.application.brief.port.out.BriefEditionGenerationExecutionPort.GenerationTarget;
import com.personal.baton.application.brief.port.out.BriefServiceClient;
import com.personal.baton.application.roundauth.ActiveAccountTeamMembershipVerifier;
import com.personal.baton.application.workspace.port.in.VerifyWorkspaceAccessUseCase;
import com.personal.baton.application.workspace.port.out.WorkspacePeopleRepository;
import com.personal.baton.application.workspace.port.out.WorkspaceOperationsRepository;
import com.personal.baton.domain.workspace.Season;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.UUID;
import com.personal.baton.application.workspace.port.in.ContinuitySignalType;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import com.personal.baton.domain.workspace.Role;
import com.personal.baton.domain.workspace.Routine;
import com.personal.baton.application.brief.port.in.BriefWorkspaceContextUseCase;
import com.personal.baton.application.brief.port.out.BriefContinuitySignalStorePort;
import org.springframework.transaction.annotation.Transactional;

public class BriefApplicationService implements BriefEditionUseCase, BriefAttentionUseCase, BriefWorkspaceContextUseCase {

    private static final Duration EXECUTION_LEASE = Duration.ofMinutes(1);

    private final VerifyWorkspaceAccessUseCase workspaceAccess;
    private final WorkspacePeopleRepository peopleRepository;
    private final WorkspaceOperationsRepository operationsRepository;
    private final ActiveAccountTeamMembershipVerifier membershipVerifier;
    private final BriefServiceClient client;
    private final BriefEditionGenerationExecutionPort executionPort;
    private final Clock clock;
    private final BriefContinuitySignalStorePort signalStore;
    private final boolean serviceEnabled;

    public BriefApplicationService(
            VerifyWorkspaceAccessUseCase workspaceAccess,
            WorkspacePeopleRepository peopleRepository,
            WorkspaceOperationsRepository operationsRepository,
            ActiveAccountTeamMembershipVerifier membershipVerifier,
            BriefServiceClient client,
            BriefEditionGenerationExecutionPort executionPort,
            Clock clock,
            BriefContinuitySignalStorePort signalStore,
            boolean serviceEnabled
    ) {
        this.workspaceAccess = workspaceAccess;
        this.peopleRepository = peopleRepository;
        this.operationsRepository = operationsRepository;
        this.membershipVerifier = membershipVerifier;
        this.client = client;
        this.executionPort = executionPort;
        this.clock = clock;
        this.signalStore = signalStore;
        this.serviceEnabled = serviceEnabled;
    }

    @Override
    @Transactional(readOnly = true)
    public List<BriefSourceContext> resolveSources(Scope scope, List<BriefSourceContext.Identity> identities) {
        verifyAttentionAccess(scope);
        Map<BriefSourceContext.Identity, UUID> ids = new HashMap<>();
        for (var identity : identities) {
            String reference = identity.sourceReference();
            if (!reference.startsWith("baton-continuity:")) continue;
            try {
                UUID id = UUID.fromString(reference.substring("baton-continuity:".length()));
                if (reference.equals("baton-continuity:" + id)) ids.put(identity, id);
            } catch (IllegalArgumentException ignored) {
                // 이전 계약이나 연결할 수 없는 참조는 이동 대상을 만들지 않는다.
            }
        }
        var signals = signalStore.findByIds(scope.teamId(), scope.seasonId(), ids.values().stream().distinct().toList())
                .stream().collect(Collectors.toMap(BriefContinuitySignalState::signalId, Function.identity()));
        Map<UUID, Optional<Role>> roles = new HashMap<>();
        Map<UUID, Optional<Routine>> routines = new HashMap<>();
        return identities.stream().map(identity -> {
            var signal = signals.get(ids.get(identity));
            BriefSourceContext.Target target = null;
            if (signal != null && signal.eventType().name().equals(identity.eventType().name())) {
                if (signal.eventType() == ContinuitySignalType.ROUTINE_REPEATEDLY_OVERDUE) {
                    var routine = routines.computeIfAbsent(signal.subjectId(), operationsRepository::findRoutineById)
                            .filter(found -> scope.seasonId().equals(found.getSeasonId()));
                    if (routine.isPresent()) {
                        var found = routine.get();
                        var role = roles.computeIfAbsent(found.getOwnerRoleId(), peopleRepository::findRoleById)
                                .filter(candidate -> scope.teamId().equals(candidate.getTeamId()) && scope.seasonId().equals(candidate.getSeasonId()));
                        if (role.isPresent()) target = new BriefSourceContext.Target(found.getTitle(), found.getOwnerRoleId(), found.getId(), found.getArchivedAt() != null);
                    }
                } else {
                    var role = roles.computeIfAbsent(signal.subjectId(), peopleRepository::findRoleById)
                            .filter(found -> scope.teamId().equals(found.getTeamId()) && scope.seasonId().equals(found.getSeasonId()));
                    if (role.isPresent()) target = new BriefSourceContext.Target(role.get().getName(), role.get().getId(), null, false);
                }
            }
            return new BriefSourceContext(identity, target);
        }).toList();
    }

    @Override
    public BriefGenerationReadiness findGenerationReadiness(Scope scope) {
        Season season = workspaceAccess.verifyRead(scope.teamId(), scope.seasonId(), scope.workspaceAccessKey());
        requireActiveMembership(scope.accountId(), scope.teamId());
        var boundary = executionPort.findDeliveryBoundary(scope.teamId(), scope.seasonId());
        Instant checkedAt = clock.instant();
        BriefGenerationReadiness.Status status;
        if (!serviceEnabled) status = BriefGenerationReadiness.Status.DISABLED;
        else if (season.isEnded()) status = BriefGenerationReadiness.Status.SEASON_ENDED;
        else if (boundary.failedCount() > 0) status = BriefGenerationReadiness.Status.DELIVERY_FAILED;
        else if (!boundary.complete()) status = BriefGenerationReadiness.Status.DELIVERY_PENDING;
        else {
            ZoneId zone = season.getZoneId();
            LocalDate weekStart = checkedAt.atZone(zone).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            var execution = executionPort.findExecutionState(new GenerationTarget(scope.teamId(), scope.seasonId(), weekStart, zone, boundary.watermark()));
            if (execution.filter(state -> state.status().equals("PERMANENT_FAILURE")).isPresent()) {
                status = BriefGenerationReadiness.Status.GENERATION_FAILED;
            } else if (execution.filter(state -> state.status().equals("PROCESSING") && state.leaseExpiresAt() != null
                    && state.leaseExpiresAt().isAfter(checkedAt)).isPresent()) {
                status = BriefGenerationReadiness.Status.GENERATING;
            } else status = BriefGenerationReadiness.Status.READY;
        }
        return new BriefGenerationReadiness(status, boundary.pendingCount(), boundary.failedCount(), boundary.lastDeliveredAt(), checkedAt);
    }

    @Override
    public BriefWeeklyResolutions summarizeWeeklyResolutions(Scope scope, BriefAttentionPage.Cursor after, int limit) {
        Season season = workspaceAccess.verifyRead(scope.teamId(), scope.seasonId(), scope.workspaceAccessKey());
        requireActiveMembership(scope.accountId(), scope.teamId());
        ZoneId zone = season.getZoneId();
        LocalDate weekStart = clock.instant().atZone(zone).toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return client.summarizeWeeklyResolutions(scope.teamId(), scope.seasonId(), weekStart, zone, after, limit);
    }

    @Override
    public BriefAttentionSummary summarizeAttention(Scope scope) {
        verifyAttentionAccess(scope);
        return client.summarizeAttention(scope.teamId(), scope.seasonId());
    }

    @Override
    public BriefAttentionPage findAttentionItems(Scope scope, BriefAttentionPage.Filter filter) {
        verifyAttentionAccess(scope);
        return client.findAttentionItems(scope.teamId(), scope.seasonId(), filter);
    }

    private void verifyAttentionAccess(Scope scope) {
        workspaceAccess.verifyRead(scope.teamId(), scope.seasonId(), scope.workspaceAccessKey());
        requireActiveMembership(scope.accountId(), scope.teamId());
    }

    @Override
    public BriefAttentionTransitions findAttentionTransitions(Scope scope, BriefAttentionTransitions.Query query) {
        verifyAttentionAccess(scope);
        return client.findAttentionTransitions(scope.teamId(), scope.seasonId(), query);
    }

    private void verifyEditionAccess(LatestEditionQuery query) {
        workspaceAccess.verifyRead(query.teamId(), query.seasonId(), query.workspaceAccessKey());
        requireActiveMembership(query.accountId(), query.teamId());
    }

    @Override
    public BriefEditionHistory findEditionHistory(LatestEditionQuery scope, BriefEditionHistory.Query query) {
        verifyEditionAccess(scope);
        return client.findEditionHistory(scope.teamId(), scope.seasonId(), query);
    }

    @Override
    public LatestEditionResult findEdition(LatestEditionQuery scope, UUID editionId) {
        verifyEditionAccess(scope);
        return readScopedEdition(scope, editionId);
    }

    @Override
    public LatestEditionResult findPreviousWeekEdition(LatestEditionQuery scope, UUID editionId) {
        verifyEditionAccess(scope);
        var selected = readScopedEdition(scope, editionId).edition();
        LocalDate previousWeek = selected.weekStart().minusWeeks(1);
        if (previousWeek.getYear() < 0) throw new BriefEditionNotFoundException();
        var previous = editionResult(scope, client.findLatestEditionForWeek(
                scope.teamId(), scope.seasonId(), previousWeek, selected.zoneId()));
        if (!previousWeek.equals(previous.edition().weekStart()) || !selected.zoneId().equals(previous.edition().zoneId())) {
            throw new BriefIntegrationConfigurationException();
        }
        return previous;
    }

    @Override
    public BriefEditionDeliveryStatus findEditionDeliveryStatus(LatestEditionQuery scope, UUID editionId) {
        verifyEditionAccess(scope);
        readScopedEdition(scope, editionId);
        var status = executionPort.findAdditionalDeliveries(scope.teamId(), scope.seasonId(), editionId)
                .map(additional -> additional ? BriefEditionDeliveryStatus.Status.ADDITIONAL_DELIVERIES
                        : BriefEditionDeliveryStatus.Status.NO_ADDITIONAL_DELIVERIES)
                .orElse(BriefEditionDeliveryStatus.Status.UNKNOWN);
        return new BriefEditionDeliveryStatus(editionId, status, clock.instant());
    }

    private LatestEditionResult readScopedEdition(LatestEditionQuery scope, UUID editionId) {
        var result = client.findEdition(editionId);
        if (result.outcome() == BriefServiceClient.Outcome.COMPLETED
                && !scopeMatches(result.edition(), scope.teamId(), scope.seasonId())) {
            throw new BriefEditionNotFoundException();
        }
        var found = editionResult(scope, result);
        if (!editionId.equals(found.edition().editionId())) {
            throw new BriefIntegrationConfigurationException();
        }
        return found;
    }

    @Override
    public BriefEditionComparison compareEditions(LatestEditionQuery scope, UUID fromEditionId, UUID toEditionId) {
        verifyEditionAccess(scope);
        readScopedEdition(scope, fromEditionId);
        if (!fromEditionId.equals(toEditionId)) readScopedEdition(scope, toEditionId);
        return client.compareEditions(fromEditionId, toEditionId);
    }

    @Override
    public LatestEditionResult findLatestEdition(LatestEditionQuery query) {
        verifyEditionAccess(query);
        return editionResult(query, client.findLatestEdition(query.teamId(), query.seasonId()));
    }

    private LatestEditionResult editionResult(LatestEditionQuery query, BriefServiceClient.Result result) {
        return switch (result.outcome()) {
            case COMPLETED -> {
                if (!scopeMatches(result.edition(), query.teamId(), query.seasonId())) {
                    throw new BriefIntegrationConfigurationException();
                }
                yield new LatestEditionResult(result.edition(), result.etag());
            }
            case NOT_FOUND -> throw new BriefEditionNotFoundException();
            case INVALID_REQUEST, AUTHENTICATION_FAILURE, PERMANENT_FAILURE ->
                    throw new BriefIntegrationConfigurationException();
            case RETRYABLE_FAILURE -> throw new BriefIntegrationUnavailableException();
        };
    }

    @Override
    public GenerationResult generateEdition(GenerateEditionCommand command) {
        Season season = workspaceAccess.verifyMutation(
                command.teamId(),
                command.seasonId(),
                command.workspaceAccessKey()
        );
        requireActiveMembership(command.accountId(), command.teamId());

        ZoneId zoneId = season.getZoneId();
        LocalDate weekStart = LocalDate.now(clock.withZone(zoneId))
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        DeliveryBoundary boundary = executionPort.findDeliveryBoundary(
                command.teamId(),
                command.seasonId()
        );
        GenerationTarget target = new GenerationTarget(
                command.teamId(),
                command.seasonId(),
                weekStart,
                zoneId,
                boundary.watermark()
        );
        Instant claimedAt = clock.instant();
        ClaimResult claim = executionPort.claim(
                target,
                boundary.complete(),
                claimedAt,
                EXECUTION_LEASE
        );
        return switch (claim) {
            case ClaimResult.DeliveryIncomplete ignored ->
                    throw new BriefGenerationBlockedException();
            case ClaimResult.InProgress ignored ->
                    throw new BriefGenerationInProgressException();
            case ClaimResult.PermanentlyFailed ignored ->
                    throw new BriefIntegrationConfigurationException();
            case ClaimResult.Completed completed -> generationResult(
                    completed.executionId(),
                    boundary.watermark(),
                    completed.editionId(),
                    completed.generation(),
                    completed.sourceCursor(),
                    completed.etag(),
                    completed.created()
            );
            case ClaimResult.Claimed claimed -> executeGeneration(
                    claimed,
                    target,
                    boundary.watermark()
            );
        };
    }

    private GenerationResult executeGeneration(
            ClaimResult.Claimed claimed,
            GenerationTarget target,
            long deliveryWatermark
    ) {
        BriefServiceClient.Result result = client.generateEdition(
                target.teamId(),
                target.seasonId(),
                target.weekStart(),
                target.zoneId()
        );
        Instant completedAt = clock.instant();
        return switch (result.outcome()) {
            case COMPLETED -> {
                BriefEditionSnapshot edition = result.edition();
                if (!scopeMatches(edition, target.teamId(), target.seasonId())
                        || !target.weekStart().equals(edition.weekStart())
                        || !target.zoneId().equals(edition.zoneId())) {
                    requireMarked(executionPort.markPermanentFailure(
                            claimed.executionId(),
                            claimed.leaseToken(),
                            completedAt,
                            "BRIEF_SCOPE_MISMATCH"
                    ));
                    throw new BriefIntegrationConfigurationException();
                }
                requireMarked(executionPort.markSucceeded(
                        claimed.executionId(),
                        claimed.leaseToken(),
                        completedAt,
                        edition.editionId(),
                        edition.generation(),
                        edition.sourceCursor(),
                        result.etag(),
                        result.created()
                ));
                yield generationResult(
                        claimed.executionId(),
                        deliveryWatermark,
                        edition.editionId(),
                        edition.generation(),
                        edition.sourceCursor(),
                        result.etag(),
                        result.created()
                );
            }
            case RETRYABLE_FAILURE -> {
                requireMarked(executionPort.markRetryableFailure(
                        claimed.executionId(),
                        claimed.leaseToken(),
                        completedAt,
                        result.code()
                ));
                throw new BriefIntegrationUnavailableException();
            }
            case NOT_FOUND, INVALID_REQUEST, AUTHENTICATION_FAILURE, PERMANENT_FAILURE -> {
                requireMarked(executionPort.markPermanentFailure(
                        claimed.executionId(),
                        claimed.leaseToken(),
                        completedAt,
                        result.code()
                ));
                throw new BriefIntegrationConfigurationException();
            }
        };
    }

    private void requireActiveMembership(UUID accountId, UUID teamId) {
        if (!membershipVerifier.hasActiveMembership(accountId, teamId)) {
            throw new BriefAccessDeniedException();
        }
    }

    private boolean scopeMatches(
            BriefEditionSnapshot edition,
            UUID teamId,
            UUID seasonId
    ) {
        return edition != null
                && teamId.equals(edition.workspaceId())
                && seasonId.equals(edition.seasonId());
    }

    private GenerationResult generationResult(
            UUID executionId,
            long deliveryWatermark,
            UUID editionId,
            long generation,
            long sourceCursor,
            String etag,
            boolean created
    ) {
        return new GenerationResult(
                executionId,
                deliveryWatermark,
                editionId,
                generation,
                sourceCursor,
                etag,
                created
        );
    }

    private void requireMarked(boolean marked) {
        if (!marked) {
            throw new IllegalStateException("BRIEF 주간 요약 생성 작업이 만료됐습니다");
        }
    }
}
