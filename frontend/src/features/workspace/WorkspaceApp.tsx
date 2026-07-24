import { useEffect, useState } from 'react'
import type { ReactNode } from 'react'
import { ApiError } from '@/shared/api/ApiError'
import { Icon } from '@/shared/ui/Icon'
import { saveAccessKey } from './api'
import type { WorkspaceScope } from './api'
import {
  clearPendingAccessKeyRotation,
  idempotencyKeyForAccessKeyRotation,
  pendingAccessKeyRotation,
} from './pendingAccessKeyChange'
import {
  useDecisionArchiveMutation,
  useHandoffCompletionMutation,
  useHandoffItemArchiveMutation,
  useRoutineExecutionCompletionMutation,
  useRotateAccessKeyMutation,
  useUpdateDecisionMutation,
  useUpdateHandoffItemMutation,
  useUpdateRoleMutation,
  useUpdateRoleResourceMutation,
  useUpdateRoutineMutation,
  useWorkspaceQuery,
} from './queries'
import {
  pendingStorageRequiredMessage,
  useCreateDecisionCommand,
  useCreateHandoffItemCommand,
  useCreateRoleCommand,
  useCreateRoleResourceCommand,
  useCreateRoutineCommand,
  useCreateSeasonRoundCommand,
} from './useContentCreationCommand'
import {
  AccessKeyModal,
  DecisionModal,
  HandoffItemModal,
  HandoffPreview,
  RoleModal,
  RoleResourceModal,
  RoutineModal,
  SeasonRoundModal,
  ShareLinkFallback,
} from './WorkspaceModals'
import type {
  DecisionFormRequest,
  HandoffItemFormRequest,
  RoleFormRequest,
  RoleResourceFormRequest,
  RoutineFormRequest,
} from './WorkspaceModals'
import {
  HandoffView,
  MemoryView,
  MobileNav,
  MobileTopbar,
  RhythmView,
  RoleInspector,
  RolesView,
  Sidebar,
  TodayView,
  WorkspaceState,
  WorkspaceSyncStatus,
} from './WorkspaceViews'
import { mutationError } from './workspacePresentation'
import type {
  CreateDecisionRequest,
  CreateHandoffItemRequest,
  CreateSeasonRoundRequest,
  Decision,
  HandoffItem,
  Role,
  RoleResource,
  Routine,
  RoutineExecution,
  SeasonRound,
  ViewKey,
  WorkspaceProjection,
} from './types'

type ModalType = 'decision' | 'role' | 'roleResource' | 'routine' | 'round' | 'handoffItem' | 'handoffPreview' | 'shareLink' | 'accessKey' | null
type Toast = { message: string; tone: 'success' | 'error' }

type WorkspaceAppProps = WorkspaceScope & {
  accessDeniedAction?: ReactNode
  onWorkspaceLoaded?: (workspace: WorkspaceProjection) => void
}

function sortedSeasonRounds(rounds: SeasonRound[]) {
  return [...rounds].sort((left, right) => {
    const dateOrder = (left.meetingDate ?? '').localeCompare(right.meetingDate ?? '')
    if (dateOrder !== 0) return dateOrder
    const nameOrder = left.name.localeCompare(right.name, 'ko')
    return nameOrder !== 0 ? nameOrder : left.id.localeCompare(right.id)
  })
}

function isExpiredIdempotencyReplay(error: unknown) {
  return error instanceof ApiError && error.code === 'IDEMPOTENCY_REPLAY_EXPIRED'
}

function isWorkspaceAccessDenied(error: unknown) {
  return error instanceof ApiError && error.code === 'WORKSPACE_ACCESS_DENIED'
}

function isWorkspaceContentConflict(error: unknown) {
  return error instanceof ApiError && error.code === 'WORKSPACE_CONTENT_CONFLICT'
}

function replaceAccessKeyFragment(accessKey?: string) {
  const fragment = accessKey ? `#accessKey=${encodeURIComponent(accessKey)}` : ''
  window.history.replaceState(
    window.history.state,
    '',
    `${window.location.pathname}${window.location.search}${fragment}`,
  )
}

export default function WorkspaceApp({ teamId, seasonId, accessKey, accessDeniedAction, onWorkspaceLoaded }: WorkspaceAppProps) {
  const [currentAccessKey, setCurrentAccessKey] = useState(accessKey)
  const scope = { teamId, seasonId, accessKey: currentAccessKey }
  const workspaceQuery = useWorkspaceQuery(scope)
  const roleCreationCommand = useCreateRoleCommand(scope)
  const updateRoleMutation = useUpdateRoleMutation(scope)
  const roleResourceCreationCommand = useCreateRoleResourceCommand(scope)
  const updateRoleResourceMutation = useUpdateRoleResourceMutation(scope)
  const routineCreationCommand = useCreateRoutineCommand(scope)
  const updateRoutineMutation = useUpdateRoutineMutation(scope)
  const roundCreationCommand = useCreateSeasonRoundCommand(scope)
  const routineExecutionCompletionMutation = useRoutineExecutionCompletionMutation(scope)
  const decisionCreationCommand = useCreateDecisionCommand(scope)
  const updateDecisionMutation = useUpdateDecisionMutation(scope)
  const decisionArchiveMutation = useDecisionArchiveMutation(scope)
  const handoffItemCreationCommand = useCreateHandoffItemCommand(scope)
  const updateHandoffItemMutation = useUpdateHandoffItemMutation(scope)
  const handoffCompletionMutation = useHandoffCompletionMutation(scope)
  const handoffItemArchiveMutation = useHandoffItemArchiveMutation(scope)
  const rotateAccessKeyMutation = useRotateAccessKeyMutation(scope)

  const [view, setView] = useState<ViewKey>('today')
  const [selectedRoleId, setSelectedRoleId] = useState('')
  const [selectedRoundId, setSelectedRoundId] = useState('')
  const [modal, setModal] = useState<ModalType>(null)
  const [editingRole, setEditingRole] = useState<Role | null>(null)
  const [editingRoleResource, setEditingRoleResource] = useState<RoleResource | null>(null)
  const [editingRoutine, setEditingRoutine] = useState<Routine | null>(null)
  const [editingDecision, setEditingDecision] = useState<Decision | null>(null)
  const [editingHandoffItem, setEditingHandoffItem] = useState<HandoffItem | null>(null)
  const [roleResourceConflictUnresolved, setRoleResourceConflictUnresolved] = useState(false)
  const [inspectorOpen, setInspectorOpen] = useState(false)
  const [toast, setToast] = useState<Toast | null>(null)
  const [rotationStorageError, setRotationStorageError] = useState('')
  const pendingRotationIdempotencyKey = pendingAccessKeyRotation(teamId)

  useEffect(() => {
    if (workspaceQuery.data) onWorkspaceLoaded?.(workspaceQuery.data)
  }, [onWorkspaceLoaded, workspaceQuery.data])

  useEffect(() => {
    const rounds = sortedSeasonRounds(workspaceQuery.data?.rounds ?? [])
    setSelectedRoundId((current) =>
      rounds.some((round) => round.id === current) ? current : rounds.at(-1)?.id ?? '',
    )
  }, [workspaceQuery.data?.rounds])

  const showToast = (message: string, tone: Toast['tone'] = 'success') => {
    setToast({ message, tone })
    window.setTimeout(() => setToast(null), 2800)
  }

  const finishAccessKeyRotation = (rotatedAccessKey: string, idempotencyKey: string) => {
    setCurrentAccessKey(rotatedAccessKey)
    const saved = saveAccessKey(teamId, rotatedAccessKey)
    replaceAccessKeyFragment(saved ? undefined : rotatedAccessKey)
    clearPendingAccessKeyRotation(teamId, idempotencyKey)
    if (saved) {
      setModal(null)
      showToast('접근 키를 바꿨어요. 이제 새 공유 링크만 사용할 수 있습니다.')
    } else {
      setModal('shareLink')
      showToast('새 키를 저장하지 못했습니다. 표시된 링크를 안전한 곳에 보관해 주세요.', 'error')
    }
  }

  const handleAccessKeyRotationError = (error: unknown, idempotencyKey: string, recovering = false) => {
    if (isExpiredIdempotencyReplay(error) || (recovering && isWorkspaceAccessDenied(error))) {
      clearPendingAccessKeyRotation(teamId, idempotencyKey)
    }
  }

  const recoverPendingAccessKeyRotation = () => {
    if (!pendingRotationIdempotencyKey || rotateAccessKeyMutation.isPending) return
    rotateAccessKeyMutation.mutate(pendingRotationIdempotencyKey, {
      onSuccess: ({ accessKey: rotatedAccessKey }) => {
        finishAccessKeyRotation(rotatedAccessKey, pendingRotationIdempotencyKey)
      },
      onError: (error) => handleAccessKeyRotationError(error, pendingRotationIdempotencyKey, true),
    })
  }

  const workspaceAccessDenied = isWorkspaceAccessDenied(workspaceQuery.error)

  if (workspaceQuery.isPending) {
    return <WorkspaceState title="작업 공간을 불러오는 중이에요" description="팀의 바통과 이번 시즌 기록을 모으고 있습니다." busy />
  }

  if (!workspaceQuery.data || workspaceAccessDenied) {
    const isAccessDenied = workspaceAccessDenied
    const pendingRotationRecovery = isAccessDenied && pendingRotationIdempotencyKey
      ? (
          <div className="workspace-key-fallback">
            <p>접근 키 변경은 서버에 반영됐지만 응답을 받지 못했을 수 있습니다.</p>
            {rotateAccessKeyMutation.error && <p className="form-error" role="alert">{mutationError(rotateAccessKeyMutation.error)}</p>}
            <button
              type="button"
              className="primary-button"
              disabled={rotateAccessKeyMutation.isPending}
              onClick={recoverPendingAccessKeyRotation}
            >
              {rotateAccessKeyMutation.isPending ? '변경 결과 확인하는 중…' : '접근 키 변경 완료 확인/복구'}
            </button>
          </div>
        )
      : undefined
    const expiredRotationRecovery = isAccessDenied && isExpiredIdempotencyReplay(rotateAccessKeyMutation.error)
      ? (
          <div className="workspace-key-fallback">
            <p className="form-error" role="alert">더 최신 접근 키 변경이 완료되어 이전 결과를 자동 복구할 수 없습니다.</p>
            <p>작업 공간 운영자에게 새 공유 링크를 요청하거나, 이미 전달받은 최신 링크가 있는지 확인해 주세요.</p>
          </div>
        )
      : undefined
    const supersededRotationRecovery = isAccessDenied && isWorkspaceAccessDenied(rotateAccessKeyMutation.error)
      ? (
          <div className="workspace-key-fallback">
            <p className="form-error" role="alert">다른 기기에서 더 최신 접근 키 변경이 완료된 것으로 보입니다.</p>
            <p>작업 공간 운영자에게 새 공유 링크를 요청하거나, 이미 전달받은 최신 링크가 있는지 확인해 주세요.</p>
          </div>
        )
      : undefined
    return (
      <WorkspaceState
        title="작업 공간을 불러오지 못했어요"
        description={mutationError(workspaceQuery.error)}
        action={expiredRotationRecovery ?? supersededRotationRecovery ?? pendingRotationRecovery ?? (isAccessDenied && accessDeniedAction
          ? accessDeniedAction
          : <button type="button" className="primary-button" onClick={() => workspaceQuery.refetch()}>다시 시도하기</button>)}
      />
    )
  }

  const workspace = workspaceQuery.data
  const { roles, resources, routines, rounds, decisions, handoffItems, members } = workspace
  const activeDecisions = decisions.filter((decision) => !decision.archivedAt)
  const archivedDecisions = decisions.filter((decision) => decision.archivedAt)
  const activeHandoffItems = handoffItems.filter((item) => !item.archivedAt)
  const archivedHandoffItems = handoffItems.filter((item) => item.archivedAt)
  const activeWorkspace = {
    ...workspace,
    decisions: activeDecisions,
    handoffItems: activeHandoffItems,
  }
  const orderedRounds = sortedSeasonRounds(rounds)
  const selectedRound = orderedRounds.find((round) => round.id === selectedRoundId)
    ?? orderedRounds.at(-1)
  const selectedRole = roles.find((role) => role.id === selectedRoleId) ?? roles[0]
  const effectiveSelectedRoleId = selectedRole?.id ?? ''
  const pendingCount = selectedRound?.routineExecutions.filter((execution) => execution.status !== 'DONE').length ?? 0
  const completedCount = selectedRound?.routineExecutions.filter((execution) => execution.status === 'DONE').length ?? 0
  const shareUrl = `${window.location.origin}/teams/${encodeURIComponent(teamId)}/seasons/${encodeURIComponent(seasonId)}#accessKey=${encodeURIComponent(currentAccessKey)}`
  const hasPendingRoleCreation = modal === 'role'
    && !editingRole
    && roleCreationCommand.hasPending()
  const hasPendingRoutineCreation = modal === 'routine'
    && !editingRoutine
    && routineCreationCommand.hasPending()
  const hasPendingRoundCreation = modal === 'round' && roundCreationCommand.hasPending()
  const hasPendingDecisionCreation = modal === 'decision'
    && !editingDecision
    && decisionCreationCommand.hasPending()
  const hasPendingHandoffCreation = modal === 'handoffItem'
    && !editingHandoffItem
    && handoffItemCreationCommand.hasPending()
  const hasPendingRoleResourceCreation = modal === 'roleResource'
    && !editingRoleResource
    && roleResourceCreationCommand.hasPending()

  const selectRole = (roleId: string, openInspector = true) => {
    setSelectedRoleId(roleId)
    setInspectorOpen(openInspector)
  }

  const openView = (key: ViewKey) => {
    setView(key)
    setInspectorOpen(false)
  }

  const handoffProgress = (roleId: string) => {
    const items = activeHandoffItems.filter((item) => item.roleId === roleId)
    if (!items.length) return 0
    return Math.round((items.filter((item) => item.completed).length / items.length) * 100)
  }

  const openRoleModal = () => {
    setEditingRole(null)
    roleCreationCommand.reset()
    setModal('role')
  }

  const openRoutineModal = () => {
    if (!roles.length) {
      setView('roles')
      showToast('루틴을 연결할 역할부터 만들어 주세요.', 'error')
      return
    }
    setEditingRoutine(null)
    routineCreationCommand.reset()
    setModal('routine')
  }

  const openRoundModal = () => {
    if (!routines.length) {
      showToast('회차를 만들기 전에 반복 루틴을 하나 이상 준비해 주세요.', 'error')
      return
    }
    roundCreationCommand.reset()
    setModal('round')
  }

  const openRoleEditModal = (role: Role) => {
    updateRoleMutation.reset()
    setEditingRole(role)
    setModal('role')
  }

  const openRoleResourceModal = () => {
    if (!roles.length) {
      setView('roles')
      showToast('자료를 연결할 역할부터 만들어 주세요.', 'error')
      return
    }
    setEditingRoleResource(null)
    roleResourceCreationCommand.reset()
    setModal('roleResource')
  }

  const openRoleResourceEditModal = (resource: RoleResource) => {
    if (roleResourceConflictUnresolved) {
      showToast('최신 자료를 확인하고 있습니다. 잠시 뒤 다시 열어 주세요.', 'error')
      void refreshRoleResourcesAfterConflict()
      return
    }
    updateRoleResourceMutation.reset()
    setEditingRoleResource(resource)
    setModal('roleResource')
  }

  const openRoutineEditModal = (routine: Routine) => {
    updateRoutineMutation.reset()
    setEditingRoutine(routine)
    setModal('routine')
  }

  const openDecisionModal = () => {
    if (!roles.length || !members.length) {
      showToast('결정에 연결할 역할과 작성자부터 준비해 주세요.', 'error')
      return
    }
    setEditingDecision(null)
    decisionCreationCommand.reset()
    setModal('decision')
  }

  const openDecisionEditModal = (decision: Decision) => {
    updateDecisionMutation.reset()
    setEditingDecision(decision)
    setModal('decision')
  }

  const openHandoffItemModal = () => {
    if (!roles.length) {
      setView('roles')
      showToast('바통을 남길 역할부터 만들어 주세요.', 'error')
      return
    }
    setEditingHandoffItem(null)
    handoffItemCreationCommand.reset()
    setModal('handoffItem')
  }

  const openHandoffItemEditModal = (item: HandoffItem) => {
    updateHandoffItemMutation.reset()
    setEditingHandoffItem(item)
    setModal('handoffItem')
  }

  const addRole = (request: RoleFormRequest) => {
    roleCreationCommand.submit(request, () => {
      setModal(null)
      setView('roles')
      showToast('새 역할을 팀의 책임 지도에 추가했어요.')
    })
  }

  const updateExistingRole = (request: RoleFormRequest) => {
    if (!editingRole) return
    const roleId = editingRole.id
    updateRoleMutation.mutate({ id: roleId, request }, {
      onSuccess: () => {
        setSelectedRoleId(roleId)
        setModal(null)
        showToast('역할 정보를 수정했어요.')
      },
    })
  }

  const addRoleResource = (request: RoleResourceFormRequest) => {
    roleResourceCreationCommand.submit(request, (createdResource) => {
      setSelectedRoleId(createdResource.roleId)
      setModal(null)
      setView('roles')
      showToast('역할에 참고 자료를 연결했어요.')
    })
  }

  const refreshRoleResourcesAfterConflict = async () => {
    const refreshed = await workspaceQuery.refetch()
    if (refreshed.isSuccess) {
      setRoleResourceConflictUnresolved(false)
      showToast('다른 구성원의 최신 자료를 불러왔어요. 내용을 확인한 뒤 다시 열어 주세요.', 'error')
      return
    }
    showToast('최신 자료를 불러오지 못했어요. 연결을 확인한 뒤 다시 시도해 주세요.', 'error')
  }

  const updateExistingRoleResource = (request: RoleResourceFormRequest) => {
    if (!editingRoleResource) return
    updateRoleResourceMutation.mutate({ id: editingRoleResource.id, request }, {
      onSuccess: (updatedResource) => {
        setSelectedRoleId(updatedResource.roleId)
        setModal(null)
        setView('roles')
        showToast('자료 링크를 수정했어요.')
      },
      onError: (error) => {
        if (!isWorkspaceContentConflict(error)) return
        setRoleResourceConflictUnresolved(true)
        setEditingRoleResource(null)
        setModal(null)
        void refreshRoleResourcesAfterConflict()
      },
    })
  }

  const addRoutine = (request: RoutineFormRequest) => {
    routineCreationCommand.submit(request, () => {
      setModal(null)
      setView('rhythm')
      showToast('반복 루틴을 운영 흐름에 추가했어요.')
    })
  }

  const updateExistingRoutine = (request: RoutineFormRequest) => {
    if (!editingRoutine) return
    updateRoutineMutation.mutate({ id: editingRoutine.id, request }, {
      onSuccess: () => {
        setModal(null)
        setView('rhythm')
        showToast('루틴 정보를 수정했어요.')
      },
    })
  }

  const addSeasonRound = (request: CreateSeasonRoundRequest) => {
    roundCreationCommand.submit(request, (createdRound) => {
      setSelectedRoundId(createdRound.id)
      setModal(null)
      setView('rhythm')
      showToast(`${createdRound.name} 운영 회차를 만들었어요.`)
    })
  }

  const toggleRoutineExecution = (execution: RoutineExecution) => {
    if (!selectedRound || execution.roundId !== selectedRound.id
      || routineExecutionCompletionMutation.isPending) return
    const completed = execution.status !== 'DONE'
    routineExecutionCompletionMutation.mutate(
      { roundId: selectedRound.id, executionId: execution.id, completed },
      {
        onSuccess: () => showToast(completed ? '이번 바통을 넘겼어요.' : '완료 표시를 되돌렸어요.'),
        onError: (error) => showToast(`완료 상태를 바꾸지 못했어요. ${mutationError(error)}`, 'error'),
      },
    )
  }

  const addDecision = (request: CreateDecisionRequest) => {
    decisionCreationCommand.submit(request, () => {
      setModal(null)
      setView('memory')
      showToast('결정과 이유를 팀의 기억에 남겼어요.')
    })
  }

  const refreshRecordAfterConflict = async (message: string) => {
    const refreshed = await workspaceQuery.refetch()
    showToast(
      refreshed.isSuccess
        ? message
        : '최신 기록을 불러오지 못했어요. 연결을 확인한 뒤 다시 시도해 주세요.',
      'error',
    )
  }

  const updateExistingDecision = (request: DecisionFormRequest) => {
    if (!editingDecision) return
    updateDecisionMutation.mutate({ id: editingDecision.id, request }, {
      onSuccess: () => {
        setEditingDecision(null)
        setModal(null)
        showToast('결정 기록을 수정했어요.')
      },
      onError: (error) => {
        if (!isWorkspaceContentConflict(error)) return
        setEditingDecision(null)
        setModal(null)
        void refreshRecordAfterConflict('다른 구성원이 먼저 바꾼 최신 결정 기록을 불러왔어요.')
      },
    })
  }

  const updateDecisionArchive = (decision: Decision, archived: boolean) => {
    if (decisionArchiveMutation.isPending) return
    decisionArchiveMutation.mutate({ id: decision.id, archived }, {
      onSuccess: () => showToast(
        archived ? '결정 기록을 보관함으로 옮겼어요.' : '결정 기록을 다시 원장에 꺼냈어요.',
      ),
      onError: (error) => {
        if (isWorkspaceContentConflict(error)) {
          void refreshRecordAfterConflict('다른 구성원의 최신 결정 기록을 불러왔어요.')
          return
        }
        showToast(`결정 기록을 ${archived ? '보관' : '복원'}하지 못했어요. ${mutationError(error)}`, 'error')
      },
    })
  }

  const addHandoffItem = (request: CreateHandoffItemRequest) => {
    handoffItemCreationCommand.submit(request, (_createdItem, submittedRequest) => {
      setSelectedRoleId(submittedRequest.roleId)
      setModal(null)
      setView('handoff')
      showToast('바통북에 새 항목을 추가했어요.')
    })
  }

  const updateExistingHandoffItem = (request: HandoffItemFormRequest) => {
    if (!editingHandoffItem) return
    updateHandoffItemMutation.mutate({ id: editingHandoffItem.id, request }, {
      onSuccess: (updatedItem) => {
        setSelectedRoleId(updatedItem.roleId)
        setEditingHandoffItem(null)
        setModal(null)
        showToast('바통북 항목을 수정했어요.')
      },
      onError: (error) => {
        if (!isWorkspaceContentConflict(error)) return
        setEditingHandoffItem(null)
        setModal(null)
        void refreshRecordAfterConflict('다른 구성원이 먼저 바꾼 최신 바통 항목을 불러왔어요.')
      },
    })
  }

  const updateHandoffItemArchive = (item: HandoffItem, archived: boolean) => {
    if (handoffItemArchiveMutation.isPending) return
    handoffItemArchiveMutation.mutate({ id: item.id, archived }, {
      onSuccess: () => showToast(
        archived ? '바통북 항목을 보관함으로 옮겼어요.' : '바통북 항목을 다시 체크리스트에 꺼냈어요.',
      ),
      onError: (error) => {
        if (isWorkspaceContentConflict(error)) {
          void refreshRecordAfterConflict('다른 구성원의 최신 바통 항목을 불러왔어요.')
          return
        }
        showToast(`바통 항목을 ${archived ? '보관' : '복원'}하지 못했어요. ${mutationError(error)}`, 'error')
      },
    })
  }

  const toggleHandoff = (id: string) => {
    const item = activeHandoffItems.find((candidate) => candidate.id === id)
    if (!item || handoffCompletionMutation.isPending) return
    const completed = !item.completed
    handoffCompletionMutation.mutate(
      { id, completed },
      {
        onSuccess: () => showToast(completed ? '바통 항목을 준비했어요.' : '바통 항목을 다시 열었어요.'),
        onError: (error) => showToast(`바통 상태를 바꾸지 못했어요. ${mutationError(error)}`, 'error'),
      },
    )
  }

  const copyShareLink = async () => {
    try {
      if (!navigator.clipboard?.writeText) throw new Error('clipboard unavailable')
      await navigator.clipboard.writeText(shareUrl)
      showToast('공유 링크를 복사했어요.')
    } catch {
      setModal('shareLink')
      showToast('자동 복사가 차단되어 직접 복사할 링크를 열었어요.', 'error')
    }
  }

  const rotateWorkspaceAccessKey = () => {
    const confirmed = window.confirm('접근 키를 바꾸면 지금까지 공유한 링크는 즉시 열리지 않게 됩니다. 새 키로 교체할까요?')
    if (!confirmed) return

    const idempotencyKey = idempotencyKeyForAccessKeyRotation(teamId)
    if (!idempotencyKey) {
      rotateAccessKeyMutation.reset()
      setRotationStorageError(pendingStorageRequiredMessage)
      return
    }
    setRotationStorageError('')
    rotateAccessKeyMutation.mutate(idempotencyKey, {
      onSuccess: ({ accessKey: rotatedAccessKey }) => {
        finishAccessKeyRotation(rotatedAccessKey, idempotencyKey)
      },
      onError: (error) => handleAccessKeyRotationError(error, idempotencyKey),
    })
  }

  return (
    <div className={`app-shell ${selectedRole ? '' : 'no-inspector'}`}>
      <Sidebar workspace={activeWorkspace} view={view} onNavigate={openView} onShare={copyShareLink} onManageAccess={() => setModal('accessKey')} />

      <main className="main-surface">
        <MobileTopbar teamName={workspace.team.name} onShare={copyShareLink} onManageAccess={() => setModal('accessKey')} />
        <div className="page-stage" key={view}>
          <WorkspaceSyncStatus
            updatedAt={workspaceQuery.dataUpdatedAt}
            syncing={workspaceQuery.isFetching}
            failed={workspaceQuery.isRefetchError}
            onRefresh={() => { void workspaceQuery.refetch() }}
          />
          {view === 'today' && (
            <TodayView
              workspace={activeWorkspace}
              rounds={orderedRounds}
              selectedRound={selectedRound}
              pendingCount={pendingCount}
              completedCount={completedCount}
              onSelectRound={setSelectedRoundId}
              onAddRound={openRoundModal}
              onSelectRole={selectRole}
              onOpenDecision={openDecisionModal}
              onToggleRoutine={toggleRoutineExecution}
              onNavigate={openView}
              onAddRole={openRoleModal}
              onAddRoutine={openRoutineModal}
              onEditRoutine={openRoutineEditModal}
              routineCompletionPending={routineExecutionCompletionMutation.isPending}
            />
          )}
          {view === 'roles' && (
            <RolesView
              roles={roles}
              members={members}
              selectedRoleId={effectiveSelectedRoleId}
              onSelectRole={selectRole}
              onAddRole={openRoleModal}
              onEditRole={openRoleEditModal}
              handoffProgress={handoffProgress}
            />
          )}
          {view === 'rhythm' && (
            <RhythmView
              roles={roles}
              routines={routines}
              rounds={orderedRounds}
              selectedRound={selectedRound}
              members={members}
              onSelectRound={setSelectedRoundId}
              onAddRound={openRoundModal}
              onSelectRole={selectRole}
              onToggleRoutine={toggleRoutineExecution}
              onAddRoutine={openRoutineModal}
              onAddRole={openRoleModal}
              onEditRoutine={openRoutineEditModal}
              completionPending={routineExecutionCompletionMutation.isPending}
            />
          )}
          {view === 'memory' && (
            <MemoryView
              decisions={activeDecisions}
              archivedDecisions={archivedDecisions}
              roles={roles}
              onOpenDecision={openDecisionModal}
              onAddRole={openRoleModal}
              onSelectRole={selectRole}
              onEditDecision={openDecisionEditModal}
              onUpdateArchive={updateDecisionArchive}
              archivePending={decisionArchiveMutation.isPending}
            />
          )}
          {view === 'handoff' && (
            <HandoffView
              roles={roles}
              members={members}
              season={workspace.season}
              selectedRoleId={effectiveSelectedRoleId}
              handoffItems={activeHandoffItems}
              archivedItems={archivedHandoffItems}
              onSelectRole={(id) => selectRole(id, false)}
              onToggle={toggleHandoff}
              onEditItem={openHandoffItemEditModal}
              onUpdateArchive={updateHandoffItemArchive}
              progress={handoffProgress}
              onPreview={() => setModal('handoffPreview')}
              onAddItem={openHandoffItemModal}
              onAddRole={openRoleModal}
              completionPending={handoffCompletionMutation.isPending}
              archivePending={handoffItemArchiveMutation.isPending}
            />
          )}
        </div>
      </main>

      {selectedRole && (
        <RoleInspector
          role={selectedRole}
          members={members}
          decisions={activeDecisions}
          routines={routines}
          resources={resources.filter((resource) => resource.roleId === selectedRole.id)}
          progress={handoffProgress(selectedRole.id)}
          open={inspectorOpen}
          onClose={() => setInspectorOpen(false)}
          onAddResource={openRoleResourceModal}
          onEditResource={openRoleResourceEditModal}
          onOpenHandoff={() => {
            setView('handoff')
            setInspectorOpen(false)
          }}
        />
      )}

      <MobileNav view={view} onNavigate={openView} />

      {modal === 'decision' && (
        <DecisionModal
          roles={roles}
          members={members}
          selectedRoleId={effectiveSelectedRoleId}
          decision={editingDecision ?? undefined}
          pending={editingDecision
            ? updateDecisionMutation.isPending
            : decisionCreationCommand.isPending}
          error={editingDecision ? updateDecisionMutation.error : decisionCreationCommand.error}
          storageError={editingDecision ? '' : decisionCreationCommand.storageError}
          recoveryAvailable={editingDecision ? false : hasPendingDecisionCreation}
          onClose={() => setModal(null)}
          onSave={editingDecision ? updateExistingDecision : addDecision}
        />
      )}
      {modal === 'role' && (
        <RoleModal
          members={members}
          season={workspace.season}
          role={editingRole ?? undefined}
          pending={editingRole
            ? updateRoleMutation.isPending
            : roleCreationCommand.isPending}
          error={editingRole ? updateRoleMutation.error : roleCreationCommand.error}
          storageError={editingRole ? '' : roleCreationCommand.storageError}
          recoveryAvailable={editingRole ? false : hasPendingRoleCreation}
          onClose={() => setModal(null)}
          onSave={editingRole ? updateExistingRole : addRole}
        />
      )}
      {modal === 'routine' && (
        <RoutineModal
          roles={roles}
          selectedRoleId={effectiveSelectedRoleId}
          routine={editingRoutine ?? undefined}
          pending={editingRoutine
            ? updateRoutineMutation.isPending
            : routineCreationCommand.isPending}
          error={editingRoutine ? updateRoutineMutation.error : routineCreationCommand.error}
          storageError={editingRoutine ? '' : routineCreationCommand.storageError}
          recoveryAvailable={editingRoutine ? false : hasPendingRoutineCreation}
          onClose={() => setModal(null)}
          onSave={editingRoutine ? updateExistingRoutine : addRoutine}
        />
      )}
      {modal === 'roleResource' && (
        <RoleResourceModal
          roles={roles}
          selectedRoleId={effectiveSelectedRoleId}
          resource={editingRoleResource ?? undefined}
          pending={editingRoleResource
            ? updateRoleResourceMutation.isPending
            : roleResourceCreationCommand.isPending}
          error={editingRoleResource
            ? updateRoleResourceMutation.error
            : roleResourceCreationCommand.error}
          storageError={editingRoleResource ? '' : roleResourceCreationCommand.storageError}
          recoveryAvailable={editingRoleResource ? false : hasPendingRoleResourceCreation}
          onClose={() => setModal(null)}
          onSave={editingRoleResource ? updateExistingRoleResource : addRoleResource}
        />
      )}
      {modal === 'round' && (
        <SeasonRoundModal
          season={workspace.season}
          roundCount={rounds.length}
          pending={roundCreationCommand.isPending}
          error={roundCreationCommand.error}
          storageError={roundCreationCommand.storageError}
          recoveryAvailable={hasPendingRoundCreation}
          onClose={() => setModal(null)}
          onSave={addSeasonRound}
        />
      )}
      {modal === 'handoffItem' && (
        <HandoffItemModal
          roles={roles}
          selectedRoleId={effectiveSelectedRoleId}
          item={editingHandoffItem ?? undefined}
          pending={editingHandoffItem
            ? updateHandoffItemMutation.isPending
            : handoffItemCreationCommand.isPending}
          error={editingHandoffItem
            ? updateHandoffItemMutation.error
            : handoffItemCreationCommand.error}
          storageError={editingHandoffItem ? '' : handoffItemCreationCommand.storageError}
          recoveryAvailable={editingHandoffItem ? false : hasPendingHandoffCreation}
          onClose={() => setModal(null)}
          onSave={editingHandoffItem ? updateExistingHandoffItem : addHandoffItem}
        />
      )}
      {modal === 'handoffPreview' && selectedRole && (
        <HandoffPreview
          role={selectedRole}
          members={members}
          routines={routines.filter((routine) => routine.ownerRoleId === selectedRole.id)}
          decisions={activeDecisions}
          resources={resources.filter((resource) => resource.roleId === selectedRole.id)}
          items={activeHandoffItems.filter((item) => item.roleId === selectedRole.id)}
          progress={handoffProgress(selectedRole.id)}
          onClose={() => setModal(null)}
        />
      )}
      {modal === 'shareLink' && <ShareLinkFallback shareUrl={shareUrl} onClose={() => setModal(null)} />}
      {modal === 'accessKey' && (
        <AccessKeyModal
          pending={rotateAccessKeyMutation.isPending}
          error={rotateAccessKeyMutation.error}
          storageError={rotationStorageError}
          onClose={() => setModal(null)}
          onShare={copyShareLink}
          onRotate={rotateWorkspaceAccessKey}
        />
      )}

      {toast && (
        <div className={`toast ${toast.tone === 'error' ? 'toast-error' : ''}`} role="status">
          <Icon name={toast.tone === 'error' ? 'alert' : 'check'} size={16} />{toast.message}
        </div>
      )}
    </div>
  )
}
