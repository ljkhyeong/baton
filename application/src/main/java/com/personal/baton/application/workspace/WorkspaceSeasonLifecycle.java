package com.personal.baton.application.workspace;

import com.personal.baton.application.workspace.WorkspaceContentIdempotency.ContentCreationAttempt;
import com.personal.baton.application.workspace.error.RoleHandoffStateConflictException;
import com.personal.baton.application.workspace.error.SeasonNameConflictException;
import com.personal.baton.application.workspace.error.SeasonSuccessorExistsException;
import com.personal.baton.application.workspace.error.WorkspaceContentConflictException;
import com.personal.baton.application.workspace.error.WorkspaceNotFoundException;
import com.personal.baton.application.workspace.port.in.WorkspaceSeasonUseCase.CopiedRoleResult;
import com.personal.baton.application.workspace.port.in.WorkspaceSeasonUseCase.CopiedRoutineResult;
import com.personal.baton.application.workspace.port.in.WorkspaceSeasonUseCase.CreateNextSeasonCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceSeasonUseCase.NextSeasonResult;
import com.personal.baton.application.workspace.port.in.WorkspaceSeasonUseCase.SeasonResult;
import com.personal.baton.application.workspace.port.in.WorkspaceSeasonUseCase.UpdateRoundScheduleCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceSeasonUseCase.UpdateSeasonCommand;
import com.personal.baton.application.workspace.port.out.WorkspaceRepository;
import com.personal.baton.domain.workspace.ContentCreationOperation;
import com.personal.baton.domain.workspace.DomainValidationException;
import com.personal.baton.domain.workspace.Role;
import com.personal.baton.domain.workspace.RoleHandoff;
import com.personal.baton.domain.workspace.Routine;
import com.personal.baton.domain.workspace.Season;
import com.personal.baton.domain.workspace.SeasonRound;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

final class WorkspaceSeasonLifecycle {

    private static final int MAX_SUCCESSOR_COPY_COUNT = 100;

    private final WorkspaceRepository repository;
    private final Clock clock;
    private final WorkspaceResultMapper resultMapper;
    private final WorkspaceContentIdempotency contentIdempotency;

    WorkspaceSeasonLifecycle(
            WorkspaceRepository repository,
            Clock clock,
            WorkspaceResultMapper resultMapper,
            WorkspaceContentIdempotency contentIdempotency
    ) {
        this.repository = repository;
        this.clock = clock;
        this.resultMapper = resultMapper;
        this.contentIdempotency = contentIdempotency;
    }

    SeasonResult updateSeason(
            UUID teamId,
            UUID seasonId,
            WorkspaceScope scope,
            UpdateSeasonCommand command
    ) {
        String normalizedName = Season.normalizeName(command.name());
        validateSeasonRangeAgainstExistingContent(
                teamId,
                seasonId,
                command.startDate(),
                command.endDate()
        );
        if (repository.existsSeasonByTeamIdAndNameAndIdNot(
                teamId,
                normalizedName,
                seasonId
        )) {
            throw new SeasonNameConflictException();
        }
        scope.season().update(normalizedName, command.startDate(), command.endDate());
        return resultMapper.toSeasonResult(repository.saveSeason(scope.season()));
    }

    SeasonResult updateRoundSchedule(
            UUID seasonId,
            WorkspaceScope scope,
            UpdateRoundScheduleCommand command
    ) {
        Season season = scope.season();
        List<SeasonRound> rounds = repository.findSeasonRoundsBySeasonId(seasonId);
        String normalizedTimeZone = Season.normalizeTimeZone(command.timeZone());
        if (!Objects.equals(season.getTimeZone(), normalizedTimeZone) && !rounds.isEmpty()) {
            throw new DomainValidationException("회차가 생성된 뒤에는 시즌 시간대를 변경할 수 없습니다");
        }
        if (command.enabled()) {
            requireDeadlineRules(repository.findRoutinesBySeasonId(seasonId));
        }

        season.configureRoundSchedule(
                command.firstMeetingDate(),
                command.meetingTime(),
                command.recurrence(),
                command.generationLeadDays(),
                command.enabled()
        );
        season.updateTimeZone(normalizedTimeZone);
        return resultMapper.toSeasonResult(repository.saveSeason(season));
    }

    SeasonResult updateSeasonEnding(
            UUID teamId,
            UUID seasonId,
            WorkspaceScope scope,
            boolean ended
    ) {
        if (ended && repository.existsOpenRoleHandoffBySeasonId(seasonId)) {
            throw handoffStateConflict(
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
        scope.season().updateEnding(ended, Instant.now(clock));
        return resultMapper.toSeasonResult(repository.saveSeason(scope.season()));
    }

    NextSeasonResult createNextSeason(
            UUID teamId,
            UUID sourceSeasonId,
            String idempotencyKey,
            WorkspaceScope scope,
            CreateNextSeasonCommand command
    ) {
        Season sourceSeason = scope.season();
        if (repository.existsOpenRoleHandoffBySeasonId(sourceSeasonId)) {
            throw handoffStateConflict(
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

        if (repository.existsSeasonByPreviousSeasonId(sourceSeasonId)) {
            throw new SeasonSuccessorExistsException();
        }
        if (repository.existsSeasonByTeamIdAndName(teamId, targetSeason.getName())) {
            throw new SeasonNameConflictException();
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

        sourceSeason.updateEnding(true, Instant.now(clock));
        Season savedSourceSeason = repository.saveSeason(sourceSeason);
        contentIdempotency.reserve(attempt);
        Season savedTargetSeason = repository.saveSeason(targetSeason);

        Map<UUID, UUID> copiedRoleIds = new HashMap<>();
        for (Role sourceRole : sourceRoles) {
            Role copiedRole = sourceRole.copyToSeason(UUID.randomUUID(), savedTargetSeason.getId());
            Role savedRole = repository.saveRole(copiedRole);
            copiedRoleIds.put(sourceRole.getId(), savedRole.getId());
        }
        for (Routine sourceRoutine : sourceRoutines) {
            UUID copiedOwnerRoleId = copiedRoleIds.get(sourceRoutine.getOwnerRoleId());
            if (copiedOwnerRoleId == null) {
                throw new IllegalStateException("복사된 루틴의 담당 역할 매핑을 찾을 수 없습니다");
            }
            repository.saveRoutine(sourceRoutine.copyToSeason(
                    UUID.randomUUID(),
                    savedTargetSeason.getId(),
                    copiedOwnerRoleId
            ));
        }
        return toNextSeasonResult(savedSourceSeason, savedTargetSeason);
    }

    private void validateSeasonRangeAgainstExistingContent(
            UUID teamId,
            UUID seasonId,
            LocalDate startDate,
            LocalDate endDate
    ) {
        LocalDate validatedStartDate = Objects.requireNonNull(startDate, "시즌 시작일은 필수입니다");
        LocalDate validatedEndDate = Objects.requireNonNull(endDate, "시즌 종료일은 필수입니다");
        if (validatedStartDate.isAfter(validatedEndDate)) {
            throw new DomainValidationException("시즌 시작일은 종료일보다 늦을 수 없습니다");
        }
        for (SeasonRound round : repository.findSeasonRoundsBySeasonId(seasonId)) {
            LocalDate meetingDate = round.getMeetingDate();
            if (meetingDate != null
                    && (meetingDate.isBefore(validatedStartDate)
                    || meetingDate.isAfter(validatedEndDate))) {
                throw new DomainValidationException("기존 회차 날짜를 제외하도록 시즌 기간을 줄일 수 없습니다");
            }
        }
        List<Role> roles = repository.findRolesByTeamIdAndSeasonId(teamId, seasonId);
        for (Role role : roles) {
            LocalDate assignmentStartDate = role.getAssignmentStartDate();
            LocalDate assignmentEndDate = role.getAssignmentEndDate();
            if ((assignmentStartDate != null
                    && (assignmentStartDate.isBefore(validatedStartDate)
                    || assignmentStartDate.isAfter(validatedEndDate)))
                    || (assignmentEndDate != null
                    && (assignmentEndDate.isBefore(validatedStartDate)
                    || assignmentEndDate.isAfter(validatedEndDate)))) {
                throw new DomainValidationException("기존 역할 배정 기간을 제외하도록 시즌 기간을 줄일 수 없습니다");
            }
        }
        List<UUID> roleIds = roles.stream().map(Role::getId).toList();
        if (!roleIds.isEmpty()) {
            for (RoleHandoff handoff : repository.findRoleHandoffsByRoleIds(roleIds)) {
                if (!handoff.isOpen()) {
                    continue;
                }
                LocalDate incomingStartDate = handoff.getIncomingAssignmentStartDate();
                LocalDate incomingEndDate = handoff.getIncomingAssignmentEndDate();
                if (incomingStartDate.isBefore(validatedStartDate)
                        || incomingStartDate.isAfter(validatedEndDate)
                        || (incomingEndDate != null
                        && (incomingEndDate.isBefore(validatedStartDate)
                        || incomingEndDate.isAfter(validatedEndDate)))) {
                    throw new DomainValidationException(
                            "준비 중인 바통의 다음 담당 기간을 제외하도록 시즌 기간을 줄일 수 없습니다"
                    );
                }
            }
        }
    }

    private void requireDeadlineRules(List<Routine> routines) {
        for (Routine routine : routines) {
            if (routine.getDeadlineDayOffset() == null || routine.getDeadlineTime() == null) {
                throw new DomainValidationException(
                        "자동 회차를 사용하려면 모든 루틴에 실제 마감 규칙이 필요합니다"
                );
            }
        }
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
                throw notFound("ROLE_NOT_FOUND", "복사할 역할을 찾을 수 없습니다");
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
            routinesById.put(routine.getId(), routine);
        }
        List<Routine> selected = new ArrayList<>();
        for (UUID routineId : routineIds) {
            Routine routine = routinesById.get(routineId);
            if (routine == null) {
                throw notFound("ROUTINE_NOT_FOUND", "복사할 루틴을 찾을 수 없습니다");
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

    private WorkspaceNotFoundException notFound(String code, String message) {
        return new WorkspaceNotFoundException(code, message);
    }

    private RoleHandoffStateConflictException handoffStateConflict(String message) {
        return new RoleHandoffStateConflictException(message);
    }
}
