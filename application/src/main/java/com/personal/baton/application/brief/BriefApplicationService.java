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
import com.personal.baton.application.roundauth.port.out.RoundAuthorizationRepository;
import com.personal.baton.application.workspace.port.in.VerifyWorkspaceAccessUseCase;
import com.personal.baton.application.workspace.port.out.WorkspaceRepository;
import com.personal.baton.domain.workspace.Member;
import com.personal.baton.domain.workspace.Season;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.UUID;

public class BriefApplicationService implements BriefEditionUseCase, BriefAttentionUseCase {

    private static final Duration EXECUTION_LEASE = Duration.ofMinutes(1);

    private final VerifyWorkspaceAccessUseCase workspaceAccess;
    private final WorkspaceRepository workspaceRepository;
    private final RoundAuthorizationRepository roundAuthorizationRepository;
    private final BriefServiceClient client;
    private final BriefEditionGenerationExecutionPort executionPort;
    private final Clock clock;

    public BriefApplicationService(
            VerifyWorkspaceAccessUseCase workspaceAccess,
            WorkspaceRepository workspaceRepository,
            RoundAuthorizationRepository roundAuthorizationRepository,
            BriefServiceClient client,
            BriefEditionGenerationExecutionPort executionPort,
            Clock clock
    ) {
        this.workspaceAccess = workspaceAccess;
        this.workspaceRepository = workspaceRepository;
        this.roundAuthorizationRepository = roundAuthorizationRepository;
        this.client = client;
        this.executionPort = executionPort;
        this.clock = clock;
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

    @Override
    public LatestEditionResult findLatestEdition(LatestEditionQuery query) {
        workspaceAccess.verifyRead(
                query.teamId(),
                query.seasonId(),
                query.workspaceAccessKey()
        );
        requireActiveMembership(query.accountId(), query.teamId());

        BriefServiceClient.Result result = client.findLatestEdition(
                query.teamId(),
                query.seasonId()
        );
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
        boolean activeMembership = roundAuthorizationRepository
                .findMembership(accountId, teamId)
                .flatMap(membership -> workspaceRepository.findMemberById(
                        membership.getMemberId()
                ))
                .filter(member -> member.getTeamId().equals(teamId))
                .filter(Member::isActive)
                .isPresent();
        if (!activeMembership) {
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
            throw new IllegalStateException("BRIEF 생성 실행 lease가 만료됐습니다");
        }
    }
}
