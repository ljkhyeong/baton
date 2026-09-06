package com.personal.baton.application.workspace;

import com.personal.baton.application.calendar.CalendarChangeRecorder;
import com.personal.baton.application.workspace.WorkspaceContentIdempotency.ContentCreationAttempt;
import com.personal.baton.application.workspace.error.RoleHandoffStateConflictException;
import com.personal.baton.application.workspace.error.WorkspaceNotFoundException;
import com.personal.baton.application.workspace.port.in.WorkspaceContract.CopiedRoleResult;
import com.personal.baton.application.workspace.port.in.WorkspaceContract.CopiedRoutineResult;
import com.personal.baton.application.workspace.port.in.WorkspaceLifecycleCommands.CreateNextSeasonCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceContract.NextSeasonResult;
import com.personal.baton.application.workspace.port.out.WorkspaceOperationsRepository;
import com.personal.baton.application.workspace.port.out.WorkspacePeopleRepository;
import com.personal.baton.application.workspace.port.out.WorkspaceRecordsRepository;
import com.personal.baton.application.workspace.port.out.WorkspaceSeasonRepository;
import com.personal.baton.application.watch.WatchMonitorChangeRecorder;
import com.personal.baton.domain.workspace.ContentCreationOperation;
import com.personal.baton.domain.workspace.DomainValidationException;
import com.personal.baton.domain.workspace.Role;
import com.personal.baton.domain.workspace.Routine;
import com.personal.baton.domain.workspace.Season;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
final class WorkspaceSeasonSuccessorCoordinator {

    private static final int MAX_SUCCESSOR_COPY_COUNT = 100;

    private final WorkspaceSeasonRepository seasonRepository;
    private final WorkspacePeopleRepository peopleRepository;
    private final WorkspaceOperationsRepository operationsRepository;
    private final WorkspaceRecordsRepository recordsRepository;
    private final Clock clock;
    private final WorkspaceContentIdempotency contentIdempotency;
    private final WorkspaceResultMapper resultMapper;
    private final WatchMonitorChangeRecorder watchMonitorChangeRecorder;
    private final CalendarChangeRecorder calendarChangeRecorder;
    private final BriefContinuitySignalRecorder briefContinuitySignalRecorder;

    WorkspaceSeasonSuccessorCoordinator(
            WorkspaceSeasonRepository seasonRepository,
            WorkspacePeopleRepository peopleRepository,
            WorkspaceOperationsRepository operationsRepository,
            WorkspaceRecordsRepository recordsRepository,
            Clock clock,
            WorkspaceContentIdempotency contentIdempotency,
            WorkspaceResultMapper resultMapper,
            WatchMonitorChangeRecorder watchMonitorChangeRecorder,
            CalendarChangeRecorder calendarChangeRecorder,
            BriefContinuitySignalRecorder briefContinuitySignalRecorder
    ) {
        this.seasonRepository = seasonRepository;
        this.peopleRepository = peopleRepository;
        this.operationsRepository = operationsRepository;
        this.recordsRepository = recordsRepository;
        this.clock = clock;
        this.contentIdempotency = contentIdempotency;
        this.resultMapper = resultMapper;
        this.watchMonitorChangeRecorder = watchMonitorChangeRecorder;
        this.calendarChangeRecorder = calendarChangeRecorder;
        this.briefContinuitySignalRecorder = briefContinuitySignalRecorder;
    }

    NextSeasonResult createNext(
            UUID teamId,
            Season sourceSeason,
            String idempotencyKey,
            CreateNextSeasonCommand command
    ) {
        UUID sourceSeasonId = sourceSeason.getId();
        if (peopleRepository.existsOpenRoleHandoffBySeasonId(sourceSeasonId)) {
            throw new RoleHandoffStateConflictException(
                    "준비 중이거나 수락을 기다리는 인수인계를 수락 또는 취소한 뒤 다음 시즌을 시작해 주세요"
            );
        }
        List<UUID> roleIds = normalizedCopyIds(command.roleIds(), "복사할 역할");
        List<UUID> routineIds = normalizedCopyIds(command.routineIds(), "복사할 반복 업무");
        UUID targetSeasonId = UUID.randomUUID();
        Season targetSeason = Season.createSuccessor(
                targetSeasonId,
                teamId,
                sourceSeasonId,
                command.name(),
                command.startDate(),
                command.endDate(),
                sourceSeason.getTimeZone()
        );
        if (!targetSeason.getStartDate().isAfter(sourceSeason.getEndDate())) {
            throw new DomainValidationException("다음 시즌 시작일은 이전 시즌 종료일보다 늦어야 합니다");
        }

        ContentCreationAttempt attempt = contentIdempotency.prepare(
                teamId,
                sourceSeasonId,
                ContentCreationOperation.SEASON,
                idempotencyKey,
                contentIdempotency.fingerprintNextSeasonRequest(
                        teamId,
                        sourceSeasonId,
                        targetSeason,
                        roleIds,
                        routineIds
                ),
                targetSeasonId
        );
        if (attempt.replayResourceId() != null) {
            Season existing = seasonRepository.findSeasonById(attempt.replayResourceId())
                    .filter(found -> found.getTeamId().equals(teamId))
                    .filter(found -> sourceSeasonId.equals(found.getPreviousSeasonId()))
                    .orElseThrow(() ->
                            contentIdempotency.missingResource(ContentCreationOperation.SEASON));
            return toNextSeasonResult(sourceSeason, existing);
        }

        seasonRepository.findActiveSeasonByTeamId(teamId)
                .filter(active -> !active.getId().equals(sourceSeasonId))
                .ifPresent(active -> {
                    throw new DomainValidationException("다른 활성 시즌이 있어 다음 시즌을 시작할 수 없습니다");
                });

        List<Role> sourceRoles = selectSourceRoles(teamId, sourceSeasonId, roleIds);
        List<Routine> sourceRoutines = selectSourceRoutines(sourceSeasonId, routineIds);
        Set<UUID> selectedRoleIds = new HashSet<>(roleIds);
        for (Routine routine : sourceRoutines) {
            if (!selectedRoleIds.contains(routine.getOwnerRoleId())) {
                throw new DomainValidationException("복사할 반복 업무의 담당 역할도 함께 선택해야 합니다");
            }
        }

        boolean sourceSeasonEndingChanged = !sourceSeason.isEnded();
        sourceSeason.updateEnding(true, Instant.now(clock));
        Season savedSourceSeason = seasonRepository.saveSeason(sourceSeason);
        if (sourceSeasonEndingChanged) {
            watchMonitorChangeRecorder.recordSeasonState(
                    recordsRepository.findRoleResourcesByTeamIdAndSeasonId(teamId, sourceSeasonId),
                    true
            );
        }
        contentIdempotency.reserve(attempt);
        Season savedTargetSeason = seasonRepository.saveSeason(targetSeason);
        calendarChangeRecorder.recordSeason(savedTargetSeason);

        Map<UUID, UUID> copiedRoleIds = new HashMap<>();
        List<Role> copiedRoles = new ArrayList<>(sourceRoles.size());
        for (Role sourceRole : sourceRoles) {
            Role copiedRole = sourceRole.copyToSeason(UUID.randomUUID(), savedTargetSeason.getId());
            copiedRoleIds.put(sourceRole.getId(), copiedRole.getId());
            copiedRoles.add(copiedRole);
        }
        if (!copiedRoles.isEmpty()) {
            peopleRepository.saveRoles(copiedRoles);
        }

        List<Routine> copiedRoutines = new ArrayList<>(sourceRoutines.size());
        for (Routine sourceRoutine : sourceRoutines) {
            UUID copiedOwnerRoleId = copiedRoleIds.get(sourceRoutine.getOwnerRoleId());
            if (copiedOwnerRoleId == null) {
                throw new IllegalStateException("복사된 반복 업무의 담당 역할 매핑을 찾을 수 없습니다");
            }
            copiedRoutines.add(sourceRoutine.copyToSeason(
                    UUID.randomUUID(),
                    savedTargetSeason.getId(),
                    copiedOwnerRoleId
            ));
        }
        if (!copiedRoutines.isEmpty()) {
            operationsRepository.saveRoutines(copiedRoutines);
        }
        briefContinuitySignalRecorder.reconcileSeason(teamId, sourceSeasonId);
        briefContinuitySignalRecorder.reconcileSeason(teamId, savedTargetSeason.getId());
        return toNextSeasonResult(savedSourceSeason, savedTargetSeason);
    }

    private List<UUID> normalizedCopyIds(List<UUID> ids, String field) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        if (ids.size() > MAX_SUCCESSOR_COPY_COUNT) {
            throw new DomainValidationException(field + "은(는) 100개를 넘을 수 없습니다");
        }
        Set<UUID> uniqueIds = new HashSet<>();
        for (UUID id : ids) {
            if (id == null) {
                throw new DomainValidationException(field + " 식별자는 null일 수 없습니다");
            }
            if (!uniqueIds.add(id)) {
                throw new DomainValidationException(field + "은(는) 중복될 수 없습니다");
            }
        }
        return uniqueIds.stream().sorted().toList();
    }

    private List<Role> selectSourceRoles(
            UUID teamId,
            UUID sourceSeasonId,
            List<UUID> roleIds
    ) {
        if (roleIds.isEmpty()) {
            return List.of();
        }
        List<Role> selected = peopleRepository.findRolesByTeamIdAndSeasonIdAndIds(teamId, sourceSeasonId, roleIds);
        if (selected.size() != roleIds.size()) {
            throw new WorkspaceNotFoundException("ROLE_NOT_FOUND", "복사할 역할을 찾을 수 없습니다");
        }
        return selected.stream().sorted(Comparator.comparing(Role::getId)).toList();
    }

    private List<Routine> selectSourceRoutines(UUID sourceSeasonId, List<UUID> routineIds) {
        if (routineIds.isEmpty()) {
            return List.of();
        }
        List<Routine> selected = operationsRepository.findActiveRoutinesBySeasonIdAndIds(sourceSeasonId, routineIds);
        if (selected.size() != routineIds.size()) {
            throw new WorkspaceNotFoundException("ROUTINE_NOT_FOUND", "복사할 반복 업무를 찾을 수 없습니다");
        }
        return selected.stream().sorted(Comparator.comparing(Routine::getId)).toList();
    }

    private NextSeasonResult toNextSeasonResult(Season sourceSeason, Season targetSeason) {
        List<CopiedRoleResult> copiedRoles = peopleRepository
                .findRolesByTeamIdAndSeasonId(targetSeason.getTeamId(), targetSeason.getId())
                .stream()
                .filter(role -> role.getPreviousRoleId() != null)
                .map(role -> new CopiedRoleResult(role.getPreviousRoleId(), role.getId()))
                .sorted((left, right) -> left.sourceRoleId().compareTo(right.sourceRoleId()))
                .toList();
        List<CopiedRoutineResult> copiedRoutines = operationsRepository
                .findRoutinesBySeasonId(targetSeason.getId())
                .stream()
                .filter(routine -> routine.getPreviousRoutineId() != null)
                .map(routine -> new CopiedRoutineResult(
                        routine.getPreviousRoutineId(),
                        routine.getId()
                ))
                .sorted((left, right) ->
                        left.sourceRoutineId().compareTo(right.sourceRoutineId()))
                .toList();
        return new NextSeasonResult(
                resultMapper.toSeasonResult(sourceSeason),
                resultMapper.toSeasonResult(targetSeason),
                copiedRoles,
                copiedRoutines
        );
    }
}
