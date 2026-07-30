package com.personal.baton.application.workspace;

import com.personal.baton.application.workspace.port.in.ScheduledRoundGenerationUseCase;
import com.personal.baton.application.workspace.port.out.WorkspaceRepository;
import com.personal.baton.application.workspace.port.out.WorkspaceRepository.ScheduledSeasonCandidate;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.stereotype.Service;

@Service
public class ScheduledRoundGenerationService implements ScheduledRoundGenerationUseCase {

    private static final Log log = LogFactory.getLog(ScheduledRoundGenerationService.class);
    private static final int MAX_OCCURRENCES_PER_SEASON_PER_TICK = 8;

    private final WorkspaceRepository repository;
    private final ScheduledRoundGenerationWorker worker;
    private final Clock clock;

    public ScheduledRoundGenerationService(
            WorkspaceRepository repository,
            ScheduledRoundGenerationWorker worker,
            Clock clock
    ) {
        this.repository = repository;
        this.worker = worker;
        this.clock = clock;
    }

    @Override
    public void generateDueRounds() {
        Instant triggeredAt = Instant.now(clock);
        List<ScheduledSeasonCandidate> candidates = repository.findScheduledSeasonCandidates();
        for (ScheduledSeasonCandidate candidate : candidates) {
            generateForSeason(candidate, triggeredAt);
        }
    }

    private void generateForSeason(ScheduledSeasonCandidate candidate, Instant triggeredAt) {
        try {
            for (int count = 0; count < MAX_OCCURRENCES_PER_SEASON_PER_TICK; count++) {
                if (!worker.generateNextOccurrence(candidate, triggeredAt)) {
                    return;
                }
            }
        } catch (RuntimeException exception) {
            log.error(
                    "자동 회차 생성에 실패했습니다. seasonId=" + candidate.seasonId(),
                    exception
            );
        }
    }
}
