package com.personal.baton.application.workspace;

import com.personal.baton.application.workspace.WorkspaceContentIdempotency.ContentCreationAttempt;
import com.personal.baton.application.workspace.error.RoleHandoffStateConflictException;
import com.personal.baton.application.workspace.error.SeasonSuccessorExistsException;
import com.personal.baton.application.workspace.error.WorkspaceContentConflictException;
import com.personal.baton.application.workspace.error.WorkspaceNotFoundException;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.CopiedRoleResult;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.CopiedRoutineResult;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.CreateNextSeasonCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.NextSeasonResult;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.SeasonResult;
import com.personal.baton.application.workspace.port.out.WorkspaceRepository;
import com.personal.baton.application.watch.WatchMonitorChangeRecorder;
import com.personal.baton.domain.workspace.ContentCreationOperation;
import com.personal.baton.domain.workspace.DomainValidationException;
import com.personal.baton.domain.workspace.Role;
import com.personal.baton.domain.workspace.RoleResource;
import com.personal.baton.domain.workspace.Routine;
import com.personal.baton.domain.workspace.Season;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class WorkspaceSeasonLifecycleCoordinator {

    private static final int MAX_SUCCESSOR_COPY_COUNT = 100;

    private final WorkspaceRepository repository;
    private final Clock clock;
    private final WorkspaceContentIdempotency contentIdempotency;
    private final WorkspaceResultMapper resultMapper;
    private final WatchMonitorChangeRecorder watchMonitorChangeRecorder;

    WorkspaceSeasonLifecycleCoordinator(
            WorkspaceRepository repository,
            Clock clock,
            WorkspaceContentIdempotency contentIdempotency,
            WorkspaceResultMapper resultMapper,
            WatchMonitorChangeRecorder watchMonitorChangeRecorder
    ) {
        this.repository = repository;
        this.clock = clock;
        this.contentIdempotency = contentIdempotency;
        this.resultMapper = resultMapper;
        this.watchMonitorChangeRecorder = watchMonitorChangeRecorder;
    }

    SeasonResult updateEnding(UUID teamId, Season season, boolean ended) {
        UUID seasonId = season.getId();
        boolean endingChanged = season.isEnded() != ended;
        if (ended && repository.existsOpenRoleHandoffBySeasonId(seasonId)) {
            throw new RoleHandoffStateConflictException(
                    "준비 중이거나 수락을 기다리는 바통을 수락 또는 취소한 뒤 시즌을 종료해 주세요"
            );
        }
        if (!ended) {
            if (repository.existsSeasonByPreviousSeasonId(seasonId)) {
                throw new SeasonSuccessorExistsException();
            }
            repository.findActiveSeasonByTeamId(teamId)
                    .filter(active -> !active.getId().equals(seasonId))
                    .ifPresent(active -> {
                        throw new WorkspaceContentConflictException();
                    });
        }

        season.updateEnding(ended, Instant.now(clock));
        Season savedSeason = repository.saveSeason(season);
        if (endingChanged) {
            watchMonitorChangeRecorder.recordSeasonState(
                    findSeasonResources(teamId, seasonId),
                    ended
            );
        }
        return resultMapper.toSeasonResult(savedSeason);
    }

    NextSeasonResult createNext(
            UUID teamId,
            Season sourceSeason,
            String idempotencyKey,
            CreateNextSeasonCommand command
    ) {
        UUID sourceSeasonId = sourceSeason.getId();
        if (repository.existsOpenRoleHandoffBySeasonId(sourceSeasonId)) {
            throw new RoleHandoffStateConflictException(
                    "준비 중이거나 수락을 기다리는 바통을 수락 또는 취소한 뒤 다음 시즌을 시작해 주세요"
            );
        }
        List<UUID> roleIds = normalizedCopyIds(command.roleIds(), "복사할 역할");
        List<UUID> routineIds = normalizedCopyIds(command.routineIds(), "복사할 루틴");
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
            Season existing = repository.findSeasonById(attempt.replayResourceId())
                    .filter(found -> found.getTeamId().equals(teamId))
                    .filter(found -> sourceSeasonId.equals(found.getPreviousSeasonId()))
                    .orElseThrow(() ->
                            contentIdempotency.missingResource(ContentCreationOperation.SEASON));
            return toNextSeasonResult(sourceSeason, existing);
        }

        repository.findActiveSeasonByTeamId(teamId)
                .filter(active -> !active.getId().equals(sourceSeasonId))
                .ifPresent(active -> {
                    throw new DomainValidationException("다른 활성 시즌이 있어 다음 시즌을 시작할 수 없습니다");
                });

        List<Role> sourceRoles = selectSourceRoles(teamId, sourceSeasonId, roleIds);
        List<Routine> sourceRoutines = selectSourceRoutines(sourceSeasonId, routineIds);
        Set<UUID> selectedRoleIds = new HashSet<>(roleIds);
        for (Routine routine : sourceRoutines) {
            if (!selectedRoleIds.contains(routine.getOwnerRoleId())) {
                throw new DomainValidationException("복사할 루틴의 담당 역할도 함께 선택해야 합니다");
            }
        }

        boolean sourceSeasonEndingChanged = !sourceSeason.isEnded();
        sourceSeason.updateEnding(true, Instant.now(clock));
        Season savedSourceSeason = repository.saveSeason(sourceSeason);
        if (sourceSeasonEndingChanged) {
            watchMonitorChangeRecorder.recordSeasonState(
                    findSeasonResources(teamId, sourceSeasonId),
                    true
            );
        }
        contentIdempotency.reserve(attempt);
        Season savedTargetSeason = repository.saveSeason(targetSeason);

        Map<UUID, UUID> copiedRoleIds = new HashMap<>();
        List<Role> copiedRoles = new ArrayList<>(sourceRoles.size());
        for (Role sourceRole : sourceRoles) {
            Role copiedRole = sourceRole.copyToSeason(UUID.randomUUID(), savedTargetSeason.getId());
            copiedRoleIds.put(sourceRole.getId(), copiedRole.getId());
            copiedRoles.add(copiedRole);
        }
        if (!copiedRoles.isEmpty()) {
            repository.saveRoles(copiedRoles);
        }

        List<Routine> copiedRoutines = new ArrayList<>(sourceRoutines.size());
        for (Routine sourceRoutine : sourceRoutines) {
            UUID copiedOwnerRoleId = copiedRoleIds.get(sourceRoutine.getOwnerRoleId());
            if (copiedOwnerRoleId == null) {
                throw new IllegalStateException("복사된 루틴의 담당 역할 매핑을 찾을 수 없습니다");
            }
            copiedRoutines.add(sourceRoutine.copyToSeason(
                    UUID.randomUUID(),
                    savedTargetSeason.getId(),
                    copiedOwnerRoleId
            ));
        }
        if (!copiedRoutines.isEmpty()) {
            repository.saveRoutines(copiedRoutines);
        }
        return toNextSeasonResult(savedSourceSeason, savedTargetSeason);
    }

    private List<RoleResource> findSeasonResources(UUID teamId, UUID seasonId) {
        List<UUID> roleIds = repository.findRolesByTeamIdAndSeasonId(teamId, seasonId).stream()
                .map(Role::getId)
                .toList();
        if (roleIds.isEmpty()) {
            return List.of();
        }
        return repository.findRoleResourcesByRoleIds(roleIds);
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
        Map<UUID, Role> rolesById = new HashMap<>();
        for (Role role : repository.findRolesByTeamIdAndSeasonId(teamId, sourceSeasonId)) {
            rolesById.put(role.getId(), role);
        }
        List<Role> selected = new ArrayList<>();
        for (UUID roleId : roleIds) {
            Role role = rolesById.get(roleId);
            if (role == null) {
                throw new WorkspaceNotFoundException(
                        "ROLE_NOT_FOUND",
                        "복사할 역할을 찾을 수 없습니다"
                );
            }
            selected.add(role);
        }
        return selected;
    }

    private List<Routine> selectSourceRoutines(UUID sourceSeasonId, List<UUID> routineIds) {
        if (routineIds.isEmpty()) {
            return List.of();
        }
        Map<UUID, Routine> routinesById = new HashMap<>();
        for (Routine routine : repository.findRoutinesBySeasonId(sourceSeasonId)) {
            if (routine.getArchivedAt() == null) {
                routinesById.put(routine.getId(), routine);
            }
        }
        List<Routine> selected = new ArrayList<>();
        for (UUID routineId : routineIds) {
            Routine routine = routinesById.get(routineId);
            if (routine == null) {
                throw new WorkspaceNotFoundException(
                        "ROUTINE_NOT_FOUND",
                        "복사할 루틴을 찾을 수 없습니다"
                );
            }
            selected.add(routine);
        }
        return selected;
    }

    private NextSeasonResult toNextSeasonResult(Season sourceSeason, Season targetSeason) {
        List<CopiedRoleResult> copiedRoles = repository
                .findRolesByTeamIdAndSeasonId(targetSeason.getTeamId(), targetSeason.getId())
                .stream()
                .filter(role -> role.getPreviousRoleId() != null)
                .map(role -> new CopiedRoleResult(role.getPreviousRoleId(), role.getId()))
                .sorted((left, right) -> left.sourceRoleId().compareTo(right.sourceRoleId()))
                .toList();
        List<CopiedRoutineResult> copiedRoutines = repository
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
