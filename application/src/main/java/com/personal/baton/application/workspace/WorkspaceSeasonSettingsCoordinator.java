package com.personal.baton.application.workspace;

import com.personal.baton.application.workspace.error.SeasonNameConflictException;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.SeasonResult;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.UpdateRoundScheduleCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.UpdateSeasonCommand;
import com.personal.baton.application.workspace.port.out.WorkspaceRepository;
import com.personal.baton.domain.workspace.DomainValidationException;
import com.personal.baton.domain.workspace.Role;
import com.personal.baton.domain.workspace.RoleHandoff;
import com.personal.baton.domain.workspace.Season;
import com.personal.baton.domain.workspace.SeasonRound;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

final class WorkspaceSeasonSettingsCoordinator {

    private final WorkspaceRepository repository;
    private final WorkspaceResultMapper resultMapper;
    private final WorkspaceRoundSchedulePolicy roundSchedulePolicy;

    WorkspaceSeasonSettingsCoordinator(
            WorkspaceRepository repository,
            WorkspaceResultMapper resultMapper,
            WorkspaceRoundSchedulePolicy roundSchedulePolicy
    ) {
        this.repository = repository;
        this.resultMapper = resultMapper;
        this.roundSchedulePolicy = roundSchedulePolicy;
    }

    SeasonResult updateSeason(
            UUID teamId,
            Season season,
            UpdateSeasonCommand command
    ) {
        UUID seasonId = season.getId();
        String normalizedName = Season.normalizeName(command.name());
        validateSeasonRangeAgainstExistingContent(
                teamId,
                seasonId,
                command.startDate(),
                command.endDate()
        );
        if (repository.existsSeasonByTeamIdAndNameAndIdNot(teamId, normalizedName, seasonId)) {
            throw new SeasonNameConflictException();
        }
        season.update(normalizedName, command.startDate(), command.endDate());
        return resultMapper.toSeasonResult(repository.saveSeason(season));
    }

    SeasonResult updateRoundSchedule(
            Season season,
            UpdateRoundScheduleCommand command
    ) {
        UUID seasonId = season.getId();
        List<SeasonRound> rounds = repository.findSeasonRoundsBySeasonId(seasonId);
        String normalizedTimeZone = Season.normalizeTimeZone(command.timeZone());
        if (!Objects.equals(season.getTimeZone(), normalizedTimeZone) && !rounds.isEmpty()) {
            throw new DomainValidationException("회차가 생성된 뒤에는 시즌 시간대를 변경할 수 없습니다");
        }
        if (command.enabled()) {
            roundSchedulePolicy.requireDeadlineRulesForScheduleActivation(seasonId);
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
}
