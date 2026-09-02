package com.personal.baton.application.workspace;

import com.personal.baton.application.workspace.port.in.ScheduledRoundGenerationUseCase;
import com.personal.baton.application.workspace.port.in.ScheduledRoundGenerationUseCase.GenerationResult;
import com.personal.baton.application.workspace.port.out.WorkspaceSeasonRepository;
import com.personal.baton.application.workspace.port.out.WorkspaceSeasonRepository.ScheduledSeasonCandidate;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.stereotype.Service;

@Service
public class ScheduledRoundGenerationService implements ScheduledRoundGenerationUseCase {

    private static final Log log = LogFactory.getLog(ScheduledRoundGenerationService.class);
    private static final int MAX_OCCURRENCES_PER_SEASON_PER_TICK = 8;

    private final WorkspaceSeasonRepository repository;
    private final ScheduledRoundGenerationWorker worker;
    private final Clock clock;

    public ScheduledRoundGenerationService(
            WorkspaceSeasonRepository repository,
            ScheduledRoundGenerationWorker worker,
            Clock clock
    ) {
        this.repository = repository;
        this.worker = worker;
        this.clock = clock;
    }

    @Override
    public GenerationResult generateDueRounds() {
        Instant triggeredAt = Instant.now(clock);
        List<ScheduledSeasonCandidate> candidates = repository.findScheduledSeasonCandidates();
        List<UUID> failedSeasonIds = new ArrayList<>();
        for (ScheduledSeasonCandidate candidate : candidates) {
            if (!generateForSeason(candidate, triggeredAt)) {
                failedSeasonIds.add(candidate.seasonId());
            }
        }
        return new GenerationResult(candidates.size(), failedSeasonIds);
    }

    private boolean generateForSeason(ScheduledSeasonCandidate candidate, Instant triggeredAt) {
        try {
            for (int count = 0; count < MAX_OCCURRENCES_PER_SEASON_PER_TICK; count++) {
                if (!worker.generateNextOccurrence(candidate, triggeredAt)) {
                    return true;
                }
            }
            return true;
        } catch (RuntimeException exception) {
            log.error(
                    "자동 회차 생성에 실패했습니다. seasonId=" + candidate.seasonId(),
                    exception
            );
            return false;
        }
    }
}
