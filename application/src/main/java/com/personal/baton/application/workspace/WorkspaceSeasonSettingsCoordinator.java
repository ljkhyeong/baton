package com.personal.baton.application.workspace;

import com.personal.baton.application.calendar.CalendarChangeRecorder;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.SeasonResult;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.UpdateRoundScheduleCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.UpdateSeasonCommand;
import com.personal.baton.application.workspace.port.out.WorkspaceRepository;
import com.personal.baton.domain.workspace.DomainValidationException;
import com.personal.baton.domain.workspace.Role;
import com.personal.baton.domain.workspace.RoleHandoff;
import com.personal.baton.domain.workspace.RoundSchedule;
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
    private final CalendarChangeRecorder calendarChangeRecorder;

    WorkspaceSeasonSettingsCoordinator(
            WorkspaceRepository repository,
            WorkspaceResultMapper resultMapper,
            WorkspaceRoundSchedulePolicy roundSchedulePolicy,
            CalendarChangeRecorder calendarChangeRecorder
    ) {
        this.repository = repository;
        this.resultMapper = resultMapper;
        this.roundSchedulePolicy = roundSchedulePolicy;
        this.calendarChangeRecorder = calendarChangeRecorder;
    }

    SeasonResult updateSeason(
            UUID teamId,
            Season season,
            UpdateSeasonCommand command
    ) {
        if (!Objects.equals(season.getStartDate(), command.startDate())
                || !Objects.equals(season.getEndDate(), command.endDate())) {
            validateSeasonRangeAgainstExistingContent(
                    teamId,
                    season.getId(),
                    command.startDate(),
                    command.endDate()
            );
        }
        season.update(command.name(), command.startDate(), command.endDate());
        Season savedSeason = repository.saveSeason(season);
        calendarChangeRecorder.recordSeason(savedSeason);
        return resultMapper.toSeasonResult(savedSeason);
    }

    SeasonResult updateRoundSchedule(
            Season season,
            UpdateRoundScheduleCommand command
    ) {
        UUID seasonId = season.getId();
        String normalizedTimeZone = Season.normalizeTimeZone(command.timeZone());
        if (!Objects.equals(season.getTimeZone(), normalizedTimeZone)
                && repository.existsSeasonRoundBySeasonId(seasonId)) {
            throw new DomainValidationException("회차가 생성된 뒤에는 시즌 시간대를 변경할 수 없습니다");
        }
        RoundSchedule currentSchedule = season.getRoundSchedule();
        if (command.enabled() && (currentSchedule == null || !currentSchedule.isEnabled())) {
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
