package com.personal.baton.application.workspace;

import com.personal.baton.application.brief.BriefContinuityEvent;
import com.personal.baton.application.brief.BriefContinuitySignalState;
import com.personal.baton.application.brief.port.out.BriefContinuitySignalStorePort;
import com.personal.baton.application.workspace.port.in.ContinuitySignalSeverity;
import com.personal.baton.application.workspace.port.in.ContinuitySignalType;
import com.personal.baton.application.workspace.port.in.WorkspaceContract.ContinuitySignalResult;
import com.personal.baton.application.workspace.port.out.WorkspaceSeasonRepository;
import com.personal.baton.domain.workspace.Season;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class BriefContinuitySignalRecorder {

    private static final int EVENT_VERSION = 2;

    private final WorkspaceSeasonRepository seasonRepository;
    private final BriefContinuitySignalStorePort storePort;
    private final Clock clock;
    private final WorkspaceContinuitySnapshotReader continuitySnapshotReader;
    private final ContinuitySignalAnalyzer analyzer;

    public BriefContinuitySignalRecorder(
            WorkspaceSeasonRepository seasonRepository,
            BriefContinuitySignalStorePort storePort,
            Clock clock,
            WorkspaceContinuitySnapshotReader continuitySnapshotReader,
            ContinuitySignalAnalyzer analyzer
    ) {
        this.seasonRepository = seasonRepository;
        this.storePort = storePort;
        this.clock = clock;
        this.continuitySnapshotReader = continuitySnapshotReader;
        this.analyzer = analyzer;
    }

    public int reconcileSeason(UUID teamId, UUID seasonId) {
        storePort.lockSeason(teamId, seasonId);
        Season season = seasonRepository.findSeasonById(seasonId)
                .filter(found -> found.getTeamId().equals(teamId))
                .orElseThrow(() -> new IllegalStateException("BRIEF 신호 재조정 시즌을 찾을 수 없습니다"));
        Instant occurredAt = clock.instant();
        Map<SignalIdentity, ContinuitySignalResult> current = currentSignals(
                teamId,
                season,
                occurredAt
        );
        Map<SignalIdentity, BriefContinuitySignalState> stored = storePort
                .findBySeason(teamId, seasonId)
                .stream()
                .collect(Collectors.toMap(
                        state -> new SignalIdentity(state.eventType(), state.subjectId()),
                        Function.identity()
                ));
        int appendedCount = 0;

        for (Map.Entry<SignalIdentity, ContinuitySignalResult> entry : current.entrySet()) {
            SignalIdentity identity = entry.getKey();
            ContinuitySignalResult signal = entry.getValue();
            BriefContinuitySignalState previous = stored.get(identity);
            if (previous == null) {
                append(
                        teamId,
                        seasonId,
                        identity,
                        UUID.randomUUID(),
                        signal.severity(),
                        BriefContinuityEvent.State.ACTIVE,
                        1,
                        occurredAt
                );
                appendedCount++;
            } else if (previous.state() != BriefContinuityEvent.State.ACTIVE
                    || previous.sourceSeverity() != signal.severity()) {
                append(
                        teamId,
                        seasonId,
                        identity,
                        previous.signalId(),
                        signal.severity(),
                        BriefContinuityEvent.State.ACTIVE,
                        previous.latestRevision() + 1,
                        occurredAt
                );
                appendedCount++;
            }
        }

        List<Map.Entry<SignalIdentity, BriefContinuitySignalState>> resolved = stored.entrySet().stream()
                .filter(entry -> entry.getValue().state() == BriefContinuityEvent.State.ACTIVE)
                .filter(entry -> !current.containsKey(entry.getKey()))
                .sorted(Map.Entry.comparingByKey(SignalIdentity.ORDER))
                .toList();
        for (Map.Entry<SignalIdentity, BriefContinuitySignalState> entry : resolved) {
            BriefContinuitySignalState previous = entry.getValue();
            append(
                    teamId,
                    seasonId,
                    entry.getKey(),
                    previous.signalId(),
                    previous.sourceSeverity(),
                    BriefContinuityEvent.State.RESOLVED,
                    previous.latestRevision() + 1,
                    occurredAt
            );
            appendedCount++;
        }
        return appendedCount;
    }

    private Map<SignalIdentity, ContinuitySignalResult> currentSignals(
            UUID teamId,
            Season season,
            Instant occurredAt
    ) {
        if (season.isEnded()) {
            return Map.of();
        }

        WorkspaceContinuitySnapshot snapshot = continuitySnapshotReader.read(teamId, season.getId());

        return analyzer.analyze(
                        Clock.fixed(occurredAt, clock.getZone()),
                        season,
                        snapshot.members(),
                        snapshot.roles(),
                        snapshot.routines(),
                        snapshot.rounds(),
                        snapshot.executions(),
                        snapshot.handoffItems(),
                        snapshot.resources(),
                        snapshot.roleHandoffs()
                ).stream()
                .collect(Collectors.toMap(
                        BriefContinuitySignalRecorder::identity,
                        Function.identity(),
                        (first, duplicate) -> {
                            throw new IllegalStateException("같은 정체성의 연속성 신호가 중복됐습니다");
                        },
                        () -> new TreeMap<>(SignalIdentity.ORDER)
                ));
    }

    private void append(
            UUID teamId,
            UUID seasonId,
            SignalIdentity identity,
            UUID signalId,
            ContinuitySignalSeverity sourceSeverity,
            BriefContinuityEvent.State state,
            long revision,
            Instant occurredAt
    ) {
        storePort.append(
                identity.subjectId(),
                new BriefContinuityEvent(
                        UUID.randomUUID(),
                        identity.eventType(),
                        EVENT_VERSION,
                        sourceSeverity,
                        teamId,
                        seasonId,
                        "baton-continuity:" + signalId,
                        revision,
                        occurredAt,
                        state
                )
        );
    }

    private static SignalIdentity identity(ContinuitySignalResult signal) {
        UUID subjectId = signal.type() == ContinuitySignalType.ROUTINE_REPEATEDLY_OVERDUE
                ? Objects.requireNonNull(signal.routineId(), "루틴 지연 신호의 routineId는 필수입니다")
                : Objects.requireNonNull(signal.roleId(), "역할 연속성 신호의 roleId는 필수입니다");
        return new SignalIdentity(signal.type(), subjectId);
    }

    private record SignalIdentity(ContinuitySignalType eventType, UUID subjectId) {

        private static final Comparator<SignalIdentity> ORDER = Comparator
                .comparing((SignalIdentity identity) -> identity.eventType().name())
                .thenComparing(identity -> identity.subjectId().toString());

    }
}
