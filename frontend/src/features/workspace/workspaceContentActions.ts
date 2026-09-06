import type { RecordDraftKind } from './RecordDraft'
import type { Dispatch, SetStateAction } from 'react'
import type { PreserveConflictDraft } from './WorkspaceConflictDraft'
import type {
  useCreateDecisionCommand,
  useCreateHandoffItemCommand,
  useCreateMemberCommand,
  useCreateRoleCommand,
  useCreateRoleResourceCommand,
  useCreateRoutineCommand,
  useCreateSeasonRoundCommand,
} from './useContentCreationCommand'
import type {
  useDecisionArchiveMutation,
  useHandoffCompletionMutation,
  useHandoffItemArchiveMutation,
  useRoleResourceArchiveMutation,
  useRoutineArchiveMutation,
  useRoutineExecutionCompletionMutation,
  useSeasonRoundArchiveMutation,
  useUpdateDecisionMutation,
  useUpdateHandoffItemMutation,
  useUpdateMemberDeactivationMutation,
  useUpdateMemberMutation,
  useUpdateRoleMutation,
  useUpdateRoleResourceMutation,
  useUpdateRoutineMutation,
  useUpdateSeasonRoundMutation,
} from './queries'
import type {
  CreateDecisionRequest,
  CreateHandoffItemRequest,
  CreateSeasonRoundRequest,
  Decision,
  HandoffItem,
  Member,
  Role,
  RoleHandoff,
  RoleResource,
  Routine,
  RoutineExecution,
  SeasonRound,
  ViewKey,
} from './types'
import type {
  DecisionFormRequest,
  HandoffItemFormRequest,
  MemberFormRequest,
  RoleFormRequest,
  RoleResourceFormRequest,
  RoutineFormRequest,
  SeasonRoundFormRequest,
} from './WorkspaceModals'
import type { WorkspaceEditor } from './workspaceEditorActions'
import {
  categoryCopy,
  isActiveMember,
  isRoleHandoffLocked,
  mutationError,
  phaseCopy,
} from './workspacePresentation'
import { ApiError } from '@/shared/api/ApiError'

type ContentCommands = {
  memberCreation: ReturnType<typeof useCreateMemberCommand>
  roleCreation: ReturnType<typeof useCreateRoleCommand>
  roleResourceCreation: ReturnType<typeof useCreateRoleResourceCommand>
  routineCreation: ReturnType<typeof useCreateRoutineCommand>
  roundCreation: ReturnType<typeof useCreateSeasonRoundCommand>
  decisionCreation: ReturnType<typeof useCreateDecisionCommand>
  handoffItemCreation: ReturnType<typeof useCreateHandoffItemCommand>
}

type ContentMutations = {
  memberUpdate: ReturnType<typeof useUpdateMemberMutation>
  memberDeactivation: ReturnType<typeof useUpdateMemberDeactivationMutation>
  roleUpdate: ReturnType<typeof useUpdateRoleMutation>
  roleResourceUpdate: ReturnType<typeof useUpdateRoleResourceMutation>
  roleResourceArchive: ReturnType<typeof useRoleResourceArchiveMutation>
  routineUpdate: ReturnType<typeof useUpdateRoutineMutation>
  routineArchive: ReturnType<typeof useRoutineArchiveMutation>
  roundUpdate: ReturnType<typeof useUpdateSeasonRoundMutation>
  roundArchive: ReturnType<typeof useSeasonRoundArchiveMutation>
  routineExecutionCompletion: ReturnType<typeof useRoutineExecutionCompletionMutation>
  decisionUpdate: ReturnType<typeof useUpdateDecisionMutation>
  decisionArchive: ReturnType<typeof useDecisionArchiveMutation>
  handoffItemUpdate: ReturnType<typeof useUpdateHandoffItemMutation>
  handoffCompletion: ReturnType<typeof useHandoffCompletionMutation>
  handoffItemArchive: ReturnType<typeof useHandoffItemArchiveMutation>
}

type WorkspaceContentActionOptions = {
  roles: Role[]
  members: Member[]
  roleHandoffs: RoleHandoff[]
  activeHandoffItems: HandoffItem[]
  selectedRound?: SeasonRound
  editingMember?: Member | null
  editingRole?: Role | null
  editingRoleResource?: RoleResource | null
  editingRoutine?: Routine | null
  editingRound?: SeasonRound | null
  editingDecision?: Decision | null
  editingHandoffItem?: HandoffItem | null
  ensureFreshWorkspace: () => boolean
  preserveConflictDraft: PreserveConflictDraft
  commands: ContentCommands
  mutations: ContentMutations
  setEditor: Dispatch<SetStateAction<WorkspaceEditor>>
  setView: Dispatch<SetStateAction<ViewKey>>
  setSelectedRoleId: Dispatch<SetStateAction<string>>
  onRecordSaved: (kind: RecordDraftKind, id: string) => void
  closeModal: () => void
  openMemberManagement: () => void
  selectRound: (roundId: string) => void
  clearSelectedRound: (roundId: string) => void
  focusRoutineArchiveResult: (routineId: string, archived: boolean) => void
  beginRoutineOperation: (routineId: string) => boolean
  endRoutineOperation: (routineId: string) => void
  beginRoundOperation: (roundId: string) => boolean
  endRoundOperation: (roundId: string) => void
  beginHandoffItemOperation: (itemId: string) => boolean
  endHandoffItemOperation: (itemId: string) => void
  notify: (message: string, tone?: 'success' | 'error') => void
}

function isWorkspaceContentConflict(error: unknown) {
  return error instanceof ApiError && error.code === 'WORKSPACE_CONTENT_CONFLICT'
}

export function createWorkspaceContentActions({
  roles,
  members,
  roleHandoffs,
  activeHandoffItems,
  selectedRound,
  editingMember,
  editingRole,
  editingRoleResource,
  editingRoutine,
  editingRound,
  editingDecision,
  editingHandoffItem,
  ensureFreshWorkspace,
  preserveConflictDraft,
  commands,
  mutations,
  setEditor,
  setView,
  setSelectedRoleId,
  onRecordSaved,
  closeModal,
  openMemberManagement,
  selectRound,
  clearSelectedRound,
  focusRoutineArchiveResult,
  beginRoutineOperation,
  endRoutineOperation,
  beginRoundOperation,
  endRoundOperation,
  beginHandoffItemOperation,
  endHandoffItemOperation,
  notify,
}: WorkspaceContentActionOptions) {
  const addRole = (request: RoleFormRequest) => commands.roleCreation.submit(request, () => {
    closeModal()
    setView('roles')
    notify('새 역할을 추가했습니다.')
  })

  const addMember = (request: MemberFormRequest) => commands.memberCreation.submit(
    request,
    (createdMember) => {
      setEditor(null)
      closeModal()
      setView('roles')
      notify(`${createdMember.name}님을 팀 구성원으로 추가했어요.`)
    },
  )

  const updateExistingMember = (request: MemberFormRequest) => {
    if (!ensureFreshWorkspace() || !editingMember) return false
    return preserveConflictDraft(
      mutations.memberUpdate.mutateAsync({ id: editingMember.id, request }, {
        onSuccess: (updatedMember) => {
          setEditor(null)
          openMemberManagement()
          notify(`${updatedMember.name}님의 표시 이름을 수정했어요.`)
        },
      }),
      '구성원 이름 수정',
      [['구성원 이름', request.name]],
    )
  }

  const toggleMemberDeactivation = (member: Member) => {
    if (!ensureFreshWorkspace() || mutations.memberDeactivation.isPending) return
    const deactivated = isActiveMember(member)
    mutations.memberDeactivation.reset()
    mutations.memberDeactivation.mutate({ id: member.id, request: { deactivated } }, {
      onSuccess: (updatedMember) => notify(deactivated
        ? `${updatedMember.name}님의 활동을 종료했어요. 기존 기록의 이름은 유지됩니다.`
        : `${updatedMember.name}님의 활동을 재개했어요.`),
      onError: (error) => {
        if (isWorkspaceContentConflict(error)) return
        notify(`구성원 활동 상태를 바꾸지 못했어요. ${mutationError(error)}`, 'error')
      },
    })
  }

  const updateExistingRole = (request: RoleFormRequest) => {
    if (!ensureFreshWorkspace() || !editingRole) return false
    if (isRoleHandoffLocked(roleHandoffs, editingRole.id)) {
      notify('인수인계 전달 후에는 수락하거나 취소해야 수정할 수 있어요.', 'error')
      return false
    }
    const roleId = editingRole.id
    return preserveConflictDraft(mutations.roleUpdate.mutateAsync({ id: roleId, request }, {
      onSuccess: () => {
        setSelectedRoleId(roleId)
        setEditor(null)
        closeModal()
        notify('역할 정보를 수정했어요.')
      },
    }), '역할 수정', [
      ['역할 이름', request.name], ['역할 목적', request.purpose],
      ['현재 담당자', members.find((member) => member.id === request.currentMemberId)?.name],
      ['다음 담당자', members.find((member) => member.id === request.nextMemberId)?.name],
      ['담당 시작일', request.assignmentStartDate], ['담당 종료일', request.assignmentEndDate],
      ['담당 업무', request.responsibilities.join('\n')], ['주의사항', request.risk],
    ])
  }

  const addRoleResource = (request: RoleResourceFormRequest) => {
    if (isRoleHandoffLocked(roleHandoffs, request.roleId)) {
      notify('전달한 인수인계는 수락하거나 취소한 뒤 자료를 추가할 수 있어요.', 'error')
      return false
    }
    return commands.roleResourceCreation.submit(request, (createdResource) => {
      onRecordSaved('resource', 'new')
      setSelectedRoleId(createdResource.roleId)
      closeModal()
      setView('roles')
      notify('역할에 참고 자료를 연결했어요.')
    })
  }

  const updateExistingRoleResource = (request: RoleResourceFormRequest) => {
    if (!ensureFreshWorkspace() || !editingRoleResource) return false
    if (isRoleHandoffLocked(roleHandoffs, editingRoleResource.roleId)
      || isRoleHandoffLocked(roleHandoffs, request.roleId)) {
      notify('전달한 인수인계는 수락하거나 취소한 뒤 자료를 수정할 수 있어요.', 'error')
      return false
    }
    return preserveConflictDraft(
      mutations.roleResourceUpdate.mutateAsync({ id: editingRoleResource.id, request }, {
        onSuccess: (updatedResource) => {
          onRecordSaved('resource', updatedResource.id)
          setSelectedRoleId(updatedResource.roleId)
          closeModal()
          setView('roles')
          notify('자료 링크를 수정했어요.')
        },
      }),
      '참고 자료 수정',
      [
        ['역할', roles.find((role) => role.id === request.roleId)?.name],
        ['자료 이름', request.title], ['자료 주소', request.url], ['설명', request.description],
      ],
    )
  }

  const updateRoleResourceArchive = (resource: RoleResource, archived: boolean) => {
    if (!ensureFreshWorkspace()) return
    if (isRoleHandoffLocked(roleHandoffs, resource.roleId)) {
      notify('전달한 인수인계는 수락하거나 취소한 뒤 자료를 보관하거나 복원할 수 있어요.', 'error')
      return
    }
    mutations.roleResourceArchive.mutate({ id: resource.id, archived }, {
      onSuccess: () => notify(archived ? '자료를 보관함으로 옮겼어요.' : '자료를 다시 연결했어요.'),
      onError: (error) => {
        if (isWorkspaceContentConflict(error)) return
        notify(`자료 상태를 바꾸지 못했어요. ${mutationError(error)}`, 'error')
      },
    })
  }

  const addRoutine = (request: RoutineFormRequest) => commands.routineCreation.submit(request, () => {
    closeModal()
    setView('rhythm')
    notify('반복 업무를 추가했습니다.')
  })

  const updateExistingRoutine = (request: RoutineFormRequest) => {
    if (!ensureFreshWorkspace() || !editingRoutine) return false
    return preserveConflictDraft(
      mutations.routineUpdate.mutateAsync({ id: editingRoutine.id, request }, {
        onSuccess: () => {
          setEditor(null)
          closeModal()
          setView('rhythm')
          notify('반복 업무 정보를 수정했어요.')
        },
      }),
      '반복 업무 수정',
      [
        ['반복 업무 이름', request.title], ['업무 시점', phaseCopy[request.phase]],
        ['마감 안내', request.dueLabel], ['세부 설명', request.detail],
        ['담당 역할', roles.find((role) => role.id === request.ownerRoleId)?.name],
        ['모임일 기준 마감일 차이', request.deadlineDayOffset], ['마감 시각', request.deadlineTime],
      ],
    )
  }

  const updateRoutineArchive = (routine: Routine, archived: boolean) => {
    if (!ensureFreshWorkspace() || !beginRoutineOperation(routine.id)) return
    void mutations.routineArchive.mutateAsync({ id: routine.id, archived })
      .then((updatedRoutine) => {
        setView('rhythm')
        notify(archived
          ? '반복 업무를 보관했어요. 이미 만든 회차의 기록은 유지됩니다.'
          : '반복 업무를 복원했습니다. 새 회차부터 포함됩니다.')
        focusRoutineArchiveResult(updatedRoutine.id, archived)
      })
      .catch((error: unknown) => {
        if (isWorkspaceContentConflict(error)) return
        notify(`반복 업무를 ${archived ? '보관' : '복원'}하지 못했어요. ${mutationError(error)}`, 'error')
      })
      .finally(() => endRoutineOperation(routine.id))
  }

  const addSeasonRound = (request: CreateSeasonRoundRequest) => commands.roundCreation.submit(
    request,
    (createdRound) => {
      selectRound(createdRound.id)
      closeModal()
      setView('rhythm')
      notify(`${createdRound.name} 회차를 만들었어요.`)
    },
  )

  const updateExistingSeasonRound = (request: SeasonRoundFormRequest) => {
    if (!ensureFreshWorkspace() || !editingRound) return false
    const roundId = editingRound.id
    if (!beginRoundOperation(roundId)) return false
    return preserveConflictDraft(
      mutations.roundUpdate.mutateAsync({ id: roundId, request }),
      '회차 정보 수정',
      [['회차 이름', request.name], ['모임 날짜', request.meetingDate]],
    )
      .then(() => {
        selectRound(roundId)
        setEditor(null)
        closeModal()
        setView('rhythm')
        notify('회차 정보를 수정했어요. 반복 업무 완료 기록은 그대로 유지됩니다.')
      })
      .catch(() => undefined)
      .finally(() => endRoundOperation(roundId))
  }

  const updateSeasonRoundArchive = (round: SeasonRound, archived: boolean) => {
    if (!ensureFreshWorkspace() || !beginRoundOperation(round.id)) return
    void mutations.roundArchive.mutateAsync({ id: round.id, archived })
      .then((updatedRound) => {
        if (archived) {
          clearSelectedRound(updatedRound.id)
          notify(round.origin === 'AUTOMATIC'
            ? '이번 회차를 건너뛰었어요. 보관함에서 복원할 수 있고 다음 반복 일정은 유지됩니다.'
            : '회차를 보관함으로 옮겼어요. 반복 업무 완료 기록은 그대로 유지됩니다.')
          return
        }
        selectRound(updatedRound.id)
        setView('rhythm')
        notify('회차를 복원했습니다.')
      })
      .catch((error: unknown) => {
        if (isWorkspaceContentConflict(error)) return
        notify(`회차를 ${archived ? '보관' : '복원'}하지 못했어요. ${mutationError(error)}`, 'error')
      })
      .finally(() => endRoundOperation(round.id))
  }

  const toggleRoutineExecution = (execution: RoutineExecution) => {
    if (!ensureFreshWorkspace() || !selectedRound || execution.roundId !== selectedRound.id) return
    const roundId = selectedRound.id
    if (!beginRoundOperation(roundId)) return
    selectRound(roundId)
    const completed = execution.status !== 'DONE'
    void mutations.routineExecutionCompletion
      .mutateAsync({ roundId, executionId: execution.id, completed })
      .then(() => notify(completed ? '이 업무를 완료로 표시했어요.' : '완료 표시를 되돌렸어요.'))
      .catch((error: unknown) => {
        if (isWorkspaceContentConflict(error)) return
        notify(`완료 상태를 바꾸지 못했어요. ${mutationError(error)}`, 'error')
      })
      .finally(() => endRoundOperation(roundId))
  }

  const addDecision = (request: CreateDecisionRequest) => commands.decisionCreation.submit(request, () => {
    onRecordSaved('decision', 'new')
    closeModal()
    setView('memory')
    notify('결정과 이유를 저장했습니다.')
  })

  const updateExistingDecision = (request: DecisionFormRequest) => {
    if (!ensureFreshWorkspace() || !editingDecision) return false
    return preserveConflictDraft(
      mutations.decisionUpdate.mutateAsync({ id: editingDecision.id, request }, {
        onSuccess: () => {
          onRecordSaved('decision', editingDecision.id)
          setEditor(null)
          closeModal()
          notify('결정 기록을 수정했어요.')
        },
      }),
      '결정 기록 수정',
      [
        ['결정', request.title], ['선택 이유', request.reason], ['검토한 대안', request.alternative],
        ['작성자', members.find((member) => member.id === request.authorMemberId)?.name],
        ['관련 역할', roles.filter((role) => request.roleIds.includes(role.id)).map((role) => role.name).join('\n')],
      ],
    )
  }

  const updateDecisionArchive = (decision: Decision, archived: boolean) => {
    if (!ensureFreshWorkspace() || mutations.decisionArchive.isPending) return
    mutations.decisionArchive.mutate({ id: decision.id, archived }, {
      onSuccess: () => notify(
        archived ? '결정 기록을 보관함으로 옮겼어요.' : '결정 기록을 복원했어요.',
      ),
      onError: (error) => {
        if (isWorkspaceContentConflict(error)) return
        notify(`결정 기록을 ${archived ? '보관' : '복원'}하지 못했어요. ${mutationError(error)}`, 'error')
      },
    })
  }

  const addHandoffItem = (request: CreateHandoffItemRequest) => {
    if (isRoleHandoffLocked(roleHandoffs, request.roleId)) {
      notify('전달한 인수인계는 수락하거나 취소한 뒤 항목을 추가할 수 있어요.', 'error')
      return false
    }
    return commands.handoffItemCreation.submit(request, (_createdItem, submittedRequest) => {
      onRecordSaved('handoff', 'new')
      setSelectedRoleId(submittedRequest.roleId)
      closeModal()
      setView('handoff')
      notify('인수인계 문서에 새 항목을 추가했어요.')
    })
  }

  const updateExistingHandoffItem = (request: HandoffItemFormRequest) => {
    if (!ensureFreshWorkspace() || !editingHandoffItem) return false
    if (isRoleHandoffLocked(roleHandoffs, editingHandoffItem.roleId)
      || isRoleHandoffLocked(roleHandoffs, request.roleId)) {
      notify('전달한 인수인계는 수락하거나 취소한 뒤 항목을 수정할 수 있어요.', 'error')
      return false
    }
    const itemId = editingHandoffItem.id
    if (!beginHandoffItemOperation(itemId)) return false
    return preserveConflictDraft(
      mutations.handoffItemUpdate.mutateAsync({ id: itemId, request }),
      '인수인계 항목 수정',
      [
        ['역할', roles.find((role) => role.id === request.roleId)?.name],
        ['남길 내용', request.label], ['항목 종류', categoryCopy[request.category]],
      ],
    )
      .then((updatedItem) => {
        onRecordSaved('handoff', updatedItem.id)
        setSelectedRoleId(updatedItem.roleId)
        setEditor(null)
        closeModal()
        notify('인수인계 항목을 수정했어요.')
      })
      .catch(() => undefined)
      .finally(() => endHandoffItemOperation(itemId))
  }

  const updateHandoffItemArchive = (item: HandoffItem, archived: boolean) => {
    if (!ensureFreshWorkspace()) return
    if (isRoleHandoffLocked(roleHandoffs, item.roleId)) {
      notify('전달한 인수인계는 수락하거나 취소한 뒤 항목을 바꿀 수 있어요.', 'error')
      return
    }
    if (!beginHandoffItemOperation(item.id)) return
    void mutations.handoffItemArchive.mutateAsync({ id: item.id, archived })
      .then(() => notify(
        archived ? '인수인계 항목을 보관함으로 옮겼어요.' : '인수인계 항목을 복원했어요.',
      ))
      .catch((error: unknown) => {
        if (isWorkspaceContentConflict(error)) return
        notify(`인수인계 항목을 ${archived ? '보관' : '복원'}하지 못했어요. ${mutationError(error)}`, 'error')
      })
      .finally(() => endHandoffItemOperation(item.id))
  }

  const toggleHandoff = (id: string) => {
    if (!ensureFreshWorkspace()) return
    const item = activeHandoffItems.find((candidate) => candidate.id === id)
    if (item && isRoleHandoffLocked(roleHandoffs, item.roleId)) {
      notify('전달한 인수인계는 수락하거나 취소한 뒤 완료 상태를 바꿀 수 있어요.', 'error')
      return
    }
    if (!item || !beginHandoffItemOperation(id)) return
    const completed = !item.completed
    void mutations.handoffCompletion.mutateAsync({ id, completed })
      .then(() => notify(completed ? '인수인계 항목을 완료로 표시했어요.' : '인수인계 항목의 완료 표시를 취소했어요.'))
      .catch((error: unknown) => {
        if (isWorkspaceContentConflict(error)) return
        notify(`인수인계 상태를 바꾸지 못했어요. ${mutationError(error)}`, 'error')
      })
      .finally(() => endHandoffItemOperation(id))
  }

  return {
    addDecision,
    addHandoffItem,
    addMember,
    addRole,
    addRoleResource,
    addRoutine,
    addSeasonRound,
    toggleHandoff,
    toggleMemberDeactivation,
    toggleRoutineExecution,
    updateDecisionArchive,
    updateExistingDecision,
    updateExistingHandoffItem,
    updateExistingMember,
    updateExistingRole,
    updateExistingRoleResource,
    updateExistingRoutine,
    updateExistingSeasonRound,
    updateHandoffItemArchive,
    updateRoleResourceArchive,
    updateRoutineArchive,
    updateSeasonRoundArchive,
  }
}
