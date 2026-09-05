import type { WorkspaceScope } from './api'
import type { PreserveConflictDraft } from './WorkspaceConflictDraft'
import {
  useUpdateRoundScheduleMutation,
  useUpdateSeasonEndingMutation,
  useUpdateSeasonMutation,
} from './queries'
import { useSeasonSuccessorCommand } from './useSeasonSuccessorCommand'
import type {
  CreateNextSeasonRequest,
  UpdateRoundScheduleRequest,
  UpdateSeasonRequest,
  WorkspaceProjection,
} from './types'

type SeasonLifecycleModal =
  | 'roundSchedule'
  | 'seasonEdit'
  | 'seasonSuccessor'
  | 'seasonSwitcher'

type SeasonLifecycleFlowOptions = {
  scope: WorkspaceScope
  workspace: WorkspaceProjection | undefined
  currentAccessKey: string
  onOpenModal: (modal: SeasonLifecycleModal) => void
  onCloseModal: () => void
  onSelectSeason: (seasonId: string, accessKey: string) => void
  onSeasonCreated: (seasonId: string, accessKey: string) => void
  notify: (message: string, tone?: 'success' | 'error') => void
  preserveConflictDraft: PreserveConflictDraft
}

export function useWorkspaceSeasonLifecycleFlow({
  scope,
  workspace,
  currentAccessKey,
  onOpenModal,
  onCloseModal,
  onSelectSeason,
  onSeasonCreated,
  notify,
  preserveConflictDraft,
}: SeasonLifecycleFlowOptions) {
  const updateSeasonMutation = useUpdateSeasonMutation(scope)
  const updateRoundScheduleMutation = useUpdateRoundScheduleMutation(scope)
  const updateSeasonEndingMutation = useUpdateSeasonEndingMutation(scope)
  const successorCommand = useSeasonSuccessorCommand(
    scope,
    workspace?.season.previousSeasonId ?? null,
  )

  const openSwitcher = () => {
    updateSeasonEndingMutation.reset()
    onOpenModal('seasonSwitcher')
  }

  const openEdit = () => {
    if (!workspace || workspace.season.endedAt) return
    updateSeasonMutation.reset()
    onOpenModal('seasonEdit')
  }

  const openRoundSchedule = () => {
    if (!workspace || workspace.season.endedAt) return
    updateRoundScheduleMutation.reset()
    onOpenModal('roundSchedule')
  }

  const openSuccessor = () => {
    if (!workspace) return
    const hasSuccessor = workspace.seasons.some((season) =>
      season.previousSeasonId === workspace.season.id)
    if (hasSuccessor) {
      openSwitcher()
      notify('이미 이어진 다음 시즌을 목록에서 열어 주세요.')
      return
    }

    successorCommand.reset()
    onOpenModal('seasonSuccessor')
  }

  const selectSeason = (seasonId: string) => {
    if (!workspace || seasonId === workspace.season.id) return
    onCloseModal()
    onSelectSeason(seasonId, currentAccessKey)
  }

  const saveSeason = (request: UpdateSeasonRequest) => {
    return preserveConflictDraft(updateSeasonMutation.mutateAsync(request, {
      onSuccess: () => {
        onCloseModal()
        notify('시즌 이름과 기간을 수정했어요.')
      },
    }), '시즌 정보 수정', [
      ['시즌 이름', request.name], ['시작일', request.startDate], ['종료일', request.endDate],
    ])
  }

  const saveRoundSchedule = (request: UpdateRoundScheduleRequest) => {
    return preserveConflictDraft(updateRoundScheduleMutation.mutateAsync(request, {
      onSuccess: () => {
        onCloseModal()
        notify(request.enabled
          ? '자동 회차 일정을 저장했어요.'
          : '자동 회차 생성을 일시중지했어요. 기존 회차는 그대로 남습니다.')
      },
    }), '자동 회차 설정', [
      ['시간대', request.timeZone], ['첫 모임일', request.firstMeetingDate], ['모임 시각', request.meetingTime],
      ['반복 주기', request.recurrence === 'WEEKLY' ? '매주' : '격주'],
      ['미리 만들 일수', request.generationLeadDays], ['자동 생성', request.enabled ? '사용' : '중지'],
    ])
  }

  const toggleEnding = () => {
    if (!workspace || updateSeasonEndingMutation.isPending) return
    const ending = !workspace.season.endedAt
    const confirmed = window.confirm(ending
      ? '시즌을 종료하면 역할, 운영, 기록과 바통을 더 이상 바꿀 수 없습니다. 종료할까요?'
      : '이 시즌을 다시 열면 기록을 다시 수정할 수 있습니다. 다시 열까요?')
    if (!confirmed) return

    updateSeasonEndingMutation.mutate({ ended: ending }, {
      onSuccess: () => notify(ending
        ? '시즌을 종료하고 기록을 읽기 전용으로 보존했어요.'
        : '시즌을 다시 열었어요.'),
    })
  }

  const createSuccessor = (request: CreateNextSeasonRequest) => {
    return successorCommand.submit(request, (result) => {
      notify('다음 시즌을 만들었어요.')
      onSeasonCreated(result.season.id, currentAccessKey)
    })
  }

  const retrySuccessorCleanup = () => {
    const cleanup = successorCommand.retryCleanup()
    if (cleanup === false) return
    void cleanup.then((completed) => {
      if (completed) notify('브라우저의 임시 요청 기록을 삭제했습니다.')
    })
  }

  return {
    actions: {
      createSuccessor,
      openEdit,
      openRoundSchedule,
      openSuccessor,
      openSwitcher,
      retrySuccessorCleanup,
      saveRoundSchedule,
      saveSeason,
      selectSeason,
      toggleEnding,
    },
    cleanup: {
      pending: successorCommand.isPending,
      visible: successorCommand.cleanupConfirmed,
    },
    edit: {
      error: updateSeasonMutation.error,
      pending: updateSeasonMutation.isPending,
    },
    roundSchedule: {
      error: updateRoundScheduleMutation.error,
      pending: updateRoundScheduleMutation.isPending,
    },
    successor: {
      cleanupRequired: successorCommand.cleanupRequired,
      error: successorCommand.error,
      pending: successorCommand.isPending,
      storageError: successorCommand.storageError,
    },
    switcher: {
      endingError: updateSeasonEndingMutation.error,
      endingPending: updateSeasonEndingMutation.isPending,
    },
  }
}
