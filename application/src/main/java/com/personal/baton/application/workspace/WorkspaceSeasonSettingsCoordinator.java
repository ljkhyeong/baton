package com.personal.baton.application.workspace;

import org.springframework.stereotype.Component;
import com.personal.baton.application.calendar.CalendarChangeRecorder;
import com.personal.baton.application.workspace.port.in.WorkspaceContract.SeasonResult;
import com.personal.baton.application.workspace.port.in.WorkspaceLifecycleCommands.UpdateRoundScheduleCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceLifecycleCommands.UpdateSeasonCommand;
import com.personal.baton.application.workspace.port.out.WorkspaceOperationsRepository;
import com.personal.baton.application.workspace.port.out.WorkspacePeopleRepository;
import com.personal.baton.application.workspace.port.out.WorkspaceSeasonRepository;
import com.personal.baton.domain.workspace.DomainValidationException;
import com.personal.baton.domain.workspace.RoundSchedule;
import com.personal.baton.domain.workspace.Season;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

@Component
final class WorkspaceSeasonSettingsCoordinator {

    private final WorkspaceSeasonRepository seasonRepository;
    private final WorkspaceOperationsRepository operationsRepository;
    private final WorkspacePeopleRepository peopleRepository;
    private final WorkspaceResultMapper resultMapper;
    private final WorkspaceRoundSchedulePolicy roundSchedulePolicy;
    private final CalendarChangeRecorder calendarChangeRecorder;

    WorkspaceSeasonSettingsCoordinator(
            WorkspaceSeasonRepository seasonRepository,
            WorkspaceOperationsRepository operationsRepository,
            WorkspacePeopleRepository peopleRepository,
            WorkspaceResultMapper resultMapper,
            WorkspaceRoundSchedulePolicy roundSchedulePolicy,
            CalendarChangeRecorder calendarChangeRecorder
    ) {
        this.seasonRepository = seasonRepository;
        this.operationsRepository = operationsRepository;
        this.peopleRepository = peopleRepository;
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
        Season savedSeason = seasonRepository.saveSeason(season);
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
                && operationsRepository.existsSeasonRoundBySeasonId(seasonId)) {
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
        return resultMapper.toSeasonResult(seasonRepository.saveSeason(season));
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
        if (operationsRepository.existsSeasonRoundOutsideRange(seasonId, validatedStartDate, validatedEndDate)) {
            throw new DomainValidationException("기존 회차 날짜를 제외하도록 시즌 기간을 줄일 수 없습니다");
        }
        if (peopleRepository.existsRoleAssignmentOutsideRange(teamId, seasonId, validatedStartDate, validatedEndDate)) {
            throw new DomainValidationException("기존 역할 배정 기간을 제외하도록 시즌 기간을 줄일 수 없습니다");
        }
        if (peopleRepository.existsOpenRoleHandoffOutsideRange(teamId, seasonId, validatedStartDate, validatedEndDate)) {
            throw new DomainValidationException(
                    "준비 중인 인수인계의 다음 담당 기간을 제외하도록 시즌 기간을 줄일 수 없습니다"
            );
        }
    }
}
